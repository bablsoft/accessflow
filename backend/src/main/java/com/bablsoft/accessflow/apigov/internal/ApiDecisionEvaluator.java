package com.bablsoft.accessflow.apigov.internal;

import com.bablsoft.accessflow.apigov.api.ApiDecisionStepKind;
import com.bablsoft.accessflow.apigov.internal.routing.ApiRoutingPolicyEngine;
import com.bablsoft.accessflow.core.api.AiOutcome;
import com.bablsoft.accessflow.core.api.DecisionTrace;
import com.bablsoft.accessflow.core.api.DecisionTraceStep;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.ReviewPlanLookupService;
import com.bablsoft.accessflow.core.api.ReviewPlanSnapshot;
import com.bablsoft.accessflow.core.api.StepOutcome;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Decides what should happen to an API call leaving {@code PENDING_AI}, and records why — without
 * doing any of it (issue AF-967).
 *
 * <p>The chain is the one production has always run: routing policies first (first enabled policy by
 * ascending priority whose condition matches wins), then the connector's require-review flags folded
 * with its review plan. What changed is that deciding and applying are no longer interleaved.
 * {@link ApiReviewStateMachine} applies the {@link ApiDecision}; the API-call simulator replays a
 * hypothetical call through this same method. A simulator with its own copy of these rules would
 * disagree with enforcement the first time either side changed, which would make it worse than
 * useless.
 *
 * <p>This class reads, and only reads: no transition, no persistence, no published event, no AI call,
 * and no request to the governed third-party API.
 */
@Component
@RequiredArgsConstructor
class ApiDecisionEvaluator {

    private final ApiRoutingPolicyEngine routingEngine;
    private final ReviewPlanLookupService reviewPlanLookupService;

    ApiDecision evaluate(ApiDecisionInput input) {
        if (input.aiOutcome() == AiOutcome.FAILED) {
            return aiFailed(input);
        }
        // Only a COMPLETED analysis carries a risk signal. Normalising here rather than trusting the
        // caller keeps the SKIPPED branch identical to production, where the listener passes no risk
        // at all, and stops a simulated verdict from leaking into a branch that never sees one.
        var risk = input.aiOutcome() == AiOutcome.COMPLETED ? input.riskLevel() : null;
        var plan = input.reviewPlanId() == null ? null
                : reviewPlanLookupService.findById(input.reviewPlanId()).orElse(null);
        var context = new ApiRoutingPolicyEngine.RoutingContext(input.verb(), input.write(),
                input.operationId(), risk);
        var steps = new ArrayList<DecisionTraceStep>(2);

        var match = routingEngine.evaluate(input.organizationId(), input.connectorId(), context);
        if (match != null) {
            return routed(input, match, plan, steps);
        }
        steps.add(DecisionTraceStep.of(ApiDecisionStepKind.ROUTING_POLICIES, StepOutcome.NO_MATCH,
                "apigov.decision.routing.no_match"));
        return byConnectorPolicy(input, plan, steps);
    }

    /**
     * AI failure lands in {@code PENDING_REVIEW} unconditionally so a human can inspect the call.
     * Routing does not run — there is no risk signal, and a failed analysis is not a positive
     * auto-decision signal — and neither does the connector's require-review fold, which could only
     * make the call <em>less</em> reviewed. The review plan is still resolved, because its approval
     * count is the one thing that still applies: a connector requiring three approvals must not drop
     * to one because the analyzer was down.
     */
    private ApiDecision aiFailed(ApiDecisionInput input) {
        var plan = input.reviewPlanId() == null ? null
                : reviewPlanLookupService.findById(input.reviewPlanId()).orElse(null);
        int approvals = plan != null ? plan.minApprovalsRequired() : 1;
        var details = connectorDetails(input, plan);
        details.put("effective_min_approvals", approvals);
        var steps = List.of(
                DecisionTraceStep.of(ApiDecisionStepKind.ROUTING_POLICIES, StepOutcome.SKIP,
                        "apigov.decision.routing.skipped_ai_failed"),
                DecisionTraceStep.of(ApiDecisionStepKind.REVIEW_REQUIREMENT, StepOutcome.SKIP,
                        "apigov.decision.review.skipped_ai_failed", details));
        return new ApiDecision(ApiDecisionKind.AI_FAILED_PENDING_REVIEW, QueryStatus.PENDING_REVIEW,
                null, approvals, new DecisionTrace(steps, QueryStatus.PENDING_REVIEW));
    }

    private ApiDecision routed(ApiDecisionInput input, ApiRoutingPolicyEngine.RoutingMatch match,
                               ReviewPlanSnapshot plan, List<DecisionTraceStep> steps) {
        var effect = switch (match.action()) {
            case AUTO_APPROVE -> new RoutedEffect(ApiDecisionKind.ROUTING_AUTO_APPROVE,
                    QueryStatus.APPROVED, null);
            case AUTO_REJECT -> new RoutedEffect(ApiDecisionKind.ROUTING_AUTO_REJECT,
                    QueryStatus.REJECTED, null);
            case REQUIRE_APPROVALS -> new RoutedEffect(ApiDecisionKind.ROUTING_REQUIRE_APPROVALS,
                    QueryStatus.PENDING_REVIEW, effectiveForRequire(match));
            case ESCALATE -> new RoutedEffect(ApiDecisionKind.ROUTING_ESCALATE,
                    QueryStatus.PENDING_REVIEW, effectiveForEscalate(match, plan));
        };
        var details = new LinkedHashMap<String, Object>();
        details.put("matched_policy_id", match.policyId());
        details.put("matched_policy_name", match.policyName());
        details.put("action", match.action().name());
        details.put("effective_min_approvals", effect.effectiveApprovals());
        steps.add(new DecisionTraceStep(ApiDecisionStepKind.ROUTING_POLICIES, StepOutcome.MATCH,
                "apigov.decision.routing.matched",
                List.of(String.valueOf(match.policyName()), match.action().name()), details));
        steps.add(DecisionTraceStep.of(ApiDecisionStepKind.REVIEW_REQUIREMENT, StepOutcome.SKIP,
                "apigov.decision.review.skipped_routing_decided", connectorDetails(input, plan)));
        return new ApiDecision(effect.kind(), effect.nextStatus(), match, effect.effectiveApprovals(),
                new DecisionTrace(steps, effect.nextStatus()));
    }

    /** The effect of one matched routing action, so the switch stays an exhaustive expression. */
    private record RoutedEffect(ApiDecisionKind kind, QueryStatus nextStatus,
                                Integer effectiveApprovals) {
    }

    /**
     * No policy matched: the connector's per-capability require-review flag decides, except that a
     * review plan explicitly waiving human approval overrides it. Mirrors the live listener exactly,
     * including the "a connector with no plan still needs one approval" default.
     */
    private ApiDecision byConnectorPolicy(ApiDecisionInput input, ReviewPlanSnapshot plan,
                                          List<DecisionTraceStep> steps) {
        boolean needsReview = input.write() ? input.requireReviewWrites() : input.requireReviewReads();
        String reasonKey = needsReview
                ? (input.write() ? "apigov.decision.review.required_write"
                                 : "apigov.decision.review.required_read")
                : "apigov.decision.review.not_required";
        if (plan != null && !plan.requiresHumanApproval()) {
            needsReview = false;
            reasonKey = "apigov.decision.review.plan_waives_approval";
        }
        var details = connectorDetails(input, plan);
        if (needsReview) {
            int approvals = plan != null ? plan.minApprovalsRequired() : 1;
            details.put("effective_min_approvals", approvals);
            steps.add(DecisionTraceStep.of(ApiDecisionStepKind.REVIEW_REQUIREMENT, StepOutcome.DENY,
                    reasonKey, details));
            return new ApiDecision(ApiDecisionKind.CONNECTOR_PENDING_REVIEW,
                    QueryStatus.PENDING_REVIEW, null, approvals,
                    new DecisionTrace(steps, QueryStatus.PENDING_REVIEW));
        }
        steps.add(DecisionTraceStep.of(ApiDecisionStepKind.REVIEW_REQUIREMENT, StepOutcome.ALLOW,
                reasonKey, details));
        return new ApiDecision(ApiDecisionKind.CONNECTOR_APPROVED, QueryStatus.APPROVED, null, null,
                new DecisionTrace(steps, QueryStatus.APPROVED));
    }

    private static LinkedHashMap<String, Object> connectorDetails(ApiDecisionInput input,
                                                                  ReviewPlanSnapshot plan) {
        var details = new LinkedHashMap<String, Object>();
        details.put("require_review_reads", input.requireReviewReads());
        details.put("require_review_writes", input.requireReviewWrites());
        details.put("review_plan_id", plan == null ? null : plan.id());
        details.put("requires_human_approval", plan == null ? null : plan.requiresHumanApproval());
        details.put("min_approvals_required", plan == null ? null : plan.minApprovalsRequired());
        return details;
    }

    private static int effectiveForRequire(ApiRoutingPolicyEngine.RoutingMatch match) {
        return match.requiredApprovals() != null ? match.requiredApprovals() : 1;
    }

    private static int effectiveForEscalate(ApiRoutingPolicyEngine.RoutingMatch match,
                                            ReviewPlanSnapshot plan) {
        int basis = plan != null ? plan.minApprovalsRequired() : 1;
        int delta = match.requiredApprovals() != null ? match.requiredApprovals() : 1;
        return basis + delta;
    }
}
