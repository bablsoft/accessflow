package com.bablsoft.accessflow.workflow.internal;

import com.bablsoft.accessflow.access.api.AccessGrantLookupService;
import com.bablsoft.accessflow.access.api.AccessGrantView;
import com.bablsoft.accessflow.core.api.QueryRequestSnapshot;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.ReviewPlanLookupService;
import com.bablsoft.accessflow.core.api.ReviewPlanSnapshot;
import com.bablsoft.accessflow.core.api.RiskLevel;
import com.bablsoft.accessflow.proxy.api.SqlParserService;
import com.bablsoft.accessflow.workflow.api.AiOutcome;
import com.bablsoft.accessflow.workflow.api.ConditionContext;
import com.bablsoft.accessflow.workflow.api.DecisionStepKind;
import com.bablsoft.accessflow.workflow.api.DecisionTrace;
import com.bablsoft.accessflow.workflow.api.DecisionTraceStep;
import com.bablsoft.accessflow.workflow.api.StepOutcome;
import com.bablsoft.accessflow.workflow.internal.routing.ConditionContextFactory;
import com.bablsoft.accessflow.workflow.internal.routing.RoutingMatch;
import com.bablsoft.accessflow.workflow.internal.routing.RoutingPolicyEngine;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Decides what should happen to a query leaving {@code PENDING_AI}, and records why — without doing
 * any of it (issue AF-859).
 *
 * <p>The chain is the one production has always run: routing policies first (first enabled policy by
 * ascending priority whose condition matches wins), then the grant-covered fast path (#582), then the
 * datasource's review plan. What changed is that deciding and applying are no longer interleaved.
 * {@link QueryReviewStateMachine} applies the {@link QueryDecision}; the access simulator replays a
 * hypothetical request through this same method. A simulator with its own copy of these rules would
 * disagree with enforcement the first time either side changed, which would make it worse than
 * useless.
 *
 * <p>This class reads, and only reads: no transition, no persistence, no published event, no AI call,
 * and no connection to a customer database.
 */
@Component
@RequiredArgsConstructor
class QueryDecisionEvaluator {

    private static final Logger log = LoggerFactory.getLogger(QueryDecisionEvaluator.class);

    private final ReviewPlanLookupService reviewPlanLookupService;
    private final ConditionContextFactory conditionContextFactory;
    // The grant fast path re-parses on its own: it must fail CLOSED on a parse failure, whereas the
    // context builder degrades to empty table signals, which here would read as "no tables" and
    // wrongly satisfy the grant's table scope.
    private final SqlParserService sqlParserService;
    private final RoutingPolicyEngine routingPolicyEngine;
    private final AccessGrantLookupService accessGrantLookupService;

    /**
     * @param riskScore the AI's numeric score, or {@code -1} when there is none — the same "absent"
     *                  sentinel the live completion event uses
     */
    QueryDecision evaluate(QueryRequestSnapshot query, AiOutcome aiOutcome, RiskLevel riskLevel,
                           int riskScore, Clock clock) {
        if (aiOutcome == AiOutcome.FAILED) {
            return aiFailed();
        }
        // Only a COMPLETED analysis carries a risk signal. Normalising here rather than trusting the
        // caller keeps the SKIPPED branch identical to production, where the listener passes no risk
        // at all, and stops a simulated verdict from leaking into a branch that never sees one.
        var effectiveRisk = aiOutcome == AiOutcome.COMPLETED ? riskLevel : null;
        var effectiveScore = aiOutcome == AiOutcome.COMPLETED ? riskScore : -1;
        var plan = reviewPlanLookupService.findForDatasource(query.datasourceId()).orElse(null);
        var context = conditionContextFactory.forLiveQuery(query, effectiveRisk, effectiveScore,
                clock);
        var steps = new ArrayList<DecisionTraceStep>(3);

        var match = routingPolicyEngine.evaluate(query.organizationId(), query.datasourceId(),
                context).orElse(null);
        if (match != null) {
            return routed(query, match, plan, context, steps);
        }
        steps.add(DecisionTraceStep.of(DecisionStepKind.ROUTING_POLICIES, StepOutcome.NO_MATCH,
                "workflow.decision.routing.no_match"));

        var grant = findCoveringGrant(query, context, steps);
        if (grant != null) {
            steps.add(DecisionTraceStep.of(DecisionStepKind.REVIEW_PLAN, StepOutcome.SKIP,
                    "workflow.decision.plan.skipped_grant_covered", planDetails(plan)));
            return new QueryDecision(QueryDecisionKind.GRANT_FAST_PATH, QueryStatus.APPROVED, null,
                    null, grant.id(), grant.approverEmail(), context,
                    new DecisionTrace(steps, QueryStatus.APPROVED));
        }

        return planned(query, plan, effectiveRisk, context, steps);
    }

    /**
     * AI failure lands in {@code PENDING_REVIEW} unconditionally so a human can inspect the query.
     * Routing does not run — there is no risk signal, and a failed analysis is not a positive
     * auto-decision signal — and neither does the grant fast path or the review plan. Decided before
     * any lookup, mirroring the live listener, which builds no context at all.
     */
    private static QueryDecision aiFailed() {
        var steps = List.of(
                DecisionTraceStep.of(DecisionStepKind.ROUTING_POLICIES, StepOutcome.SKIP,
                        "workflow.decision.routing.skipped_ai_failed"),
                DecisionTraceStep.of(DecisionStepKind.GRANT_FAST_PATH, StepOutcome.SKIP,
                        "workflow.decision.grant.skipped_ai_failed"),
                DecisionTraceStep.of(DecisionStepKind.REVIEW_PLAN, StepOutcome.SKIP,
                        "workflow.decision.plan.skipped_ai_failed"));
        return new QueryDecision(QueryDecisionKind.AI_FAILED_PENDING_REVIEW,
                QueryStatus.PENDING_REVIEW, null, null, null, null, null,
                new DecisionTrace(steps, QueryStatus.PENDING_REVIEW));
    }

    private QueryDecision routed(QueryRequestSnapshot query, RoutingMatch match,
                                 ReviewPlanSnapshot plan, ConditionContext context,
                                 List<DecisionTraceStep> steps) {
        var effect = switch (match.action()) {
            case AUTO_APPROVE -> new RoutedEffect(QueryDecisionKind.ROUTING_AUTO_APPROVE,
                    QueryStatus.APPROVED, null);
            case AUTO_REJECT -> new RoutedEffect(QueryDecisionKind.ROUTING_AUTO_REJECT,
                    QueryStatus.REJECTED, null);
            case REQUIRE_APPROVALS -> new RoutedEffect(QueryDecisionKind.ROUTING_REQUIRE_APPROVALS,
                    QueryStatus.PENDING_REVIEW, effectiveForRequire(match));
            case ESCALATE -> new RoutedEffect(QueryDecisionKind.ROUTING_ESCALATE,
                    QueryStatus.PENDING_REVIEW, effectiveForEscalate(match, plan));
        };
        var kind = effect.kind();
        var nextStatus = effect.nextStatus();
        var effective = effect.effectiveApprovals();
        var details = new LinkedHashMap<String, Object>();
        details.put("matched_policy_id", match.policyId());
        details.put("matched_policy_name", match.policyName());
        details.put("action", match.action().name());
        details.put("effective_min_approvals", effective);
        steps.add(new DecisionTraceStep(DecisionStepKind.ROUTING_POLICIES, StepOutcome.MATCH,
                "workflow.decision.routing.matched",
                List.of(String.valueOf(match.policyName()), match.action().name()), details));
        steps.add(DecisionTraceStep.of(DecisionStepKind.GRANT_FAST_PATH, StepOutcome.SKIP,
                "workflow.decision.grant.skipped_routing_decided"));
        steps.add(DecisionTraceStep.of(DecisionStepKind.REVIEW_PLAN, StepOutcome.SKIP,
                "workflow.decision.plan.skipped_routing_decided", planDetails(plan)));
        return new QueryDecision(kind, nextStatus, match, effective, null, null, context,
                new DecisionTrace(steps, nextStatus));
    }

    /**
     * Grant-covered auto-approval (#582): an active APPROVED JIT grant with
     * {@code pre_approve_queries=true} whose scope covers the query (capability + table allow-list,
     * the same semantics as the submission gate) approves it outright. Runs only after routing found
     * no match — an AUTO_REJECT / REQUIRE_APPROVALS / ESCALATE policy always wins. Suppressed on an
     * open behavioural anomaly and on HIGH/CRITICAL AI risk; the AI-skipped path (no risk signal) is
     * allowed.
     *
     * @return the first covering grant, or {@code null} to fall through to the review plan
     */
    private AccessGrantView findCoveringGrant(QueryRequestSnapshot query, ConditionContext context,
                                              List<DecisionTraceStep> steps) {
        if (context.anomalyActive()) {
            steps.add(DecisionTraceStep.of(DecisionStepKind.GRANT_FAST_PATH, StepOutcome.NO_MATCH,
                    "workflow.decision.grant.suppressed_anomaly"));
            return null;
        }
        // Gate on the risk level alone (not hasRiskSignal(), which also requires a score) so a
        // HIGH/CRITICAL verdict suppresses the fast-path even when no numeric score was reported.
        if (context.riskLevel() != null
                && context.riskLevel() != RiskLevel.LOW
                && context.riskLevel() != RiskLevel.MEDIUM) {
            steps.add(new DecisionTraceStep(DecisionStepKind.GRANT_FAST_PATH, StepOutcome.NO_MATCH,
                    "workflow.decision.grant.suppressed_risk",
                    List.of(context.riskLevel().name()), Map.of()));
            return null;
        }
        var grants = accessGrantLookupService.findActivePreApprovedGrants(
                query.organizationId(), query.submittedByUserId(), query.datasourceId());
        if (grants.isEmpty()) {
            steps.add(DecisionTraceStep.of(DecisionStepKind.GRANT_FAST_PATH, StepOutcome.NO_MATCH,
                    "workflow.decision.grant.none_active"));
            return null;
        }
        Set<String> referencedTables;
        try {
            referencedTables = sqlParserService.parse(query.sqlText()).referencedTables();
        } catch (RuntimeException ex) {
            log.warn("Grant fast-path: failed to re-parse SQL for query {}; failing closed",
                    query.id());
            steps.add(DecisionTraceStep.of(DecisionStepKind.GRANT_FAST_PATH, StepOutcome.NO_MATCH,
                    "workflow.decision.grant.parse_failed", consideredGrants(grants)));
            return null;
        }
        for (var grant : grants) {
            if (grantCovers(grant, query.queryType(), referencedTables)) {
                var details = consideredGrants(grants);
                details.put("grant_id", grant.id());
                details.put("approver_email", grant.approverEmail());
                steps.add(new DecisionTraceStep(DecisionStepKind.GRANT_FAST_PATH, StepOutcome.MATCH,
                        "workflow.decision.grant.covered", List.of(String.valueOf(grant.id())),
                        details));
                return grant;
            }
        }
        steps.add(DecisionTraceStep.of(DecisionStepKind.GRANT_FAST_PATH, StepOutcome.NO_MATCH,
                "workflow.decision.grant.no_covering_grant", consideredGrants(grants)));
        return null;
    }

    /** The effect of one matched routing action, so the switch stays an exhaustive expression. */
    private record RoutedEffect(QueryDecisionKind kind, QueryStatus nextStatus,
                                Integer effectiveApprovals) {
    }

    private QueryDecision planned(QueryRequestSnapshot query, ReviewPlanSnapshot plan,
                                  RiskLevel riskLevel, ConditionContext context,
                                  List<DecisionTraceStep> steps) {
        var details = planDetails(plan);
        QueryStatus nextStatus;
        String reasonKey;
        if (plan == null) {
            log.debug("Query {} has no review plan; routing to PENDING_REVIEW", query.id());
            nextStatus = QueryStatus.PENDING_REVIEW;
            reasonKey = "workflow.decision.plan.absent";
        } else if (!plan.requiresHumanApproval()) {
            nextStatus = QueryStatus.APPROVED;
            reasonKey = "workflow.decision.plan.no_human_approval";
        } else if (canFastPathApprove(plan, query.queryType(), riskLevel)) {
            nextStatus = QueryStatus.APPROVED;
            reasonKey = "workflow.decision.plan.auto_approve_reads";
        } else {
            nextStatus = QueryStatus.PENDING_REVIEW;
            reasonKey = "workflow.decision.plan.requires_review";
        }
        steps.add(DecisionTraceStep.of(DecisionStepKind.REVIEW_PLAN,
                nextStatus == QueryStatus.APPROVED ? StepOutcome.ALLOW : StepOutcome.DENY,
                reasonKey, details));
        var kind = nextStatus == QueryStatus.APPROVED
                ? QueryDecisionKind.PLAN_APPROVED
                : QueryDecisionKind.PLAN_PENDING_REVIEW;
        return new QueryDecision(kind, nextStatus, null, null, null, null, context,
                new DecisionTrace(steps, nextStatus));
    }

    private static LinkedHashMap<String, Object> consideredGrants(List<AccessGrantView> grants) {
        var details = new LinkedHashMap<String, Object>();
        details.put("considered_grant_ids", grants.stream().map(AccessGrantView::id).toList());
        return details;
    }

    private static Map<String, Object> planDetails(ReviewPlanSnapshot plan) {
        var details = new LinkedHashMap<String, Object>();
        details.put("review_plan_id", plan == null ? null : plan.id());
        details.put("requires_human_approval", plan != null && plan.requiresHumanApproval());
        details.put("auto_approve_reads", plan != null && plan.autoApproveReads());
        details.put("min_approvals_required", plan == null ? null : plan.minApprovalsRequired());
        return details;
    }

    private static boolean grantCovers(AccessGrantView grant, QueryType queryType,
                                       Set<String> referencedTables) {
        return DatasourcePermissionChecker.hasCapability(grant.canRead(), grant.canWrite(),
                        grant.canDdl(), queryType)
                && DatasourcePermissionChecker.rejectedTables(grant.allowedSchemas(),
                        grant.allowedTables(), referencedTables).isEmpty();
    }

    private static int effectiveForRequire(RoutingMatch match) {
        return match.requiredApprovals() != null ? match.requiredApprovals() : 1;
    }

    private static int effectiveForEscalate(RoutingMatch match, ReviewPlanSnapshot plan) {
        int basis = plan != null ? plan.minApprovalsRequired() : 1;
        int delta = match.requiredApprovals() != null ? match.requiredApprovals() : 1;
        return basis + delta;
    }

    private static boolean canFastPathApprove(ReviewPlanSnapshot plan, QueryType queryType,
                                              RiskLevel riskLevel) {
        return plan.autoApproveReads()
                && queryType == QueryType.SELECT
                && (riskLevel == RiskLevel.LOW || riskLevel == RiskLevel.MEDIUM);
    }
}
