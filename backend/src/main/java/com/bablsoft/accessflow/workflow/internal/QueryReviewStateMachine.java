package com.bablsoft.accessflow.workflow.internal;

import com.bablsoft.accessflow.ai.api.BehaviorAnomalyLookupService;
import com.bablsoft.accessflow.core.api.QueryRequestLookupService;
import com.bablsoft.accessflow.core.api.QueryRequestSnapshot;
import com.bablsoft.accessflow.core.api.QueryRequestStateService;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.ReviewPlanLookupService;
import com.bablsoft.accessflow.core.api.ReviewPlanSnapshot;
import com.bablsoft.accessflow.core.api.RiskLevel;
import com.bablsoft.accessflow.core.api.UserGroupService;
import com.bablsoft.accessflow.core.api.UserQueryService;
import com.bablsoft.accessflow.core.api.UserView;
import com.bablsoft.accessflow.core.events.AiAnalysisCompletedEvent;
import com.bablsoft.accessflow.core.events.AiAnalysisFailedEvent;
import com.bablsoft.accessflow.core.events.AiAnalysisSkippedEvent;
import com.bablsoft.accessflow.core.events.QueryAutoApprovedEvent;
import com.bablsoft.accessflow.core.events.QueryAutoRejectedEvent;
import com.bablsoft.accessflow.core.events.QueryReadyForReviewEvent;
import com.bablsoft.accessflow.proxy.api.SqlParserService;
import com.bablsoft.accessflow.workflow.api.ConditionContext;
import com.bablsoft.accessflow.workflow.internal.routing.RoutingDecisionService;
import com.bablsoft.accessflow.workflow.internal.routing.RoutingMatch;
import com.bablsoft.accessflow.workflow.internal.routing.RoutingPolicyEngine;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;

/**
 * Drives the {@code PENDING_AI → PENDING_REVIEW | APPROVED | REJECTED} transition by listening to
 * the AI module's completion / failure / skipped events. On AI completion (and on the AI-skipped
 * path) the {@link RoutingPolicyEngine} runs first: the first enabled policy by ascending priority
 * whose typed condition matches decides routing — {@code AUTO_APPROVE} / {@code AUTO_REJECT}
 * short-circuit, {@code REQUIRE_APPROVALS} / {@code ESCALATE} force human review with an effective
 * approval-count override persisted on {@code routing_decision}. On no match the query falls through
 * to the datasource's review plan exactly as before.
 *
 * <p>AI failure unconditionally lands in {@code PENDING_REVIEW} so a human can inspect the query —
 * routing policies are not run on the failure path (no risk signal, and a failed analysis is not a
 * positive auto-decision signal). The skipped path (datasource has {@code ai_analysis_enabled =
 * false}) runs routing with no risk signal — risk-based conditions evaluate to {@code false} — and
 * otherwise respects {@code plan.requires_human_approval}, never short-circuiting via
 * {@code auto_approve_reads} since the SELECT/low-risk fast-path needs an AI risk signal.
 */
@Component
@RequiredArgsConstructor
class QueryReviewStateMachine {

    private static final Logger log = LoggerFactory.getLogger(QueryReviewStateMachine.class);

    private final QueryRequestLookupService queryRequestLookupService;
    private final ReviewPlanLookupService reviewPlanLookupService;
    private final QueryRequestStateService queryRequestStateService;
    private final SqlParserService sqlParserService;
    private final UserQueryService userQueryService;
    private final UserGroupService userGroupService;
    private final RoutingPolicyEngine routingPolicyEngine;
    private final RoutingDecisionService routingDecisionService;
    private final BehaviorAnomalyLookupService behaviorAnomalyLookupService;
    private final ApplicationEventPublisher eventPublisher;

    // Time-of-day / day-of-week routing conditions evaluate in the server's local zone. A field
    // (not an injected bean) so it can be overridden in tests without colliding with the proxy's
    // UTC Clock bean.
    private Clock clock = Clock.systemDefaultZone();

    void setClock(Clock clock) {
        this.clock = clock;
    }

    @ApplicationModuleListener
    void onAiCompleted(AiAnalysisCompletedEvent event) {
        var query = queryRequestLookupService.findById(event.queryRequestId()).orElse(null);
        if (query == null) {
            log.warn("AiAnalysisCompletedEvent for unknown query {}", event.queryRequestId());
            return;
        }
        if (query.status() != QueryStatus.PENDING_AI) {
            log.warn("AiAnalysisCompletedEvent for query {} not in PENDING_AI (status={})",
                    query.id(), query.status());
            return;
        }
        var plan = reviewPlanLookupService.findForDatasource(query.datasourceId()).orElse(null);
        var context = buildContext(query, event.riskLevel(), event.riskScore());
        if (applyRoutingPolicy(query, context, plan)) {
            return;
        }
        var nextStatus = decideNextStatus(plan, query, event.riskLevel());
        queryRequestStateService.transitionTo(query.id(), QueryStatus.PENDING_AI, nextStatus);
        publishTerminalOrPending(query.id(), nextStatus);
    }

    @ApplicationModuleListener
    void onAiSkipped(AiAnalysisSkippedEvent event) {
        var query = queryRequestLookupService.findById(event.queryRequestId()).orElse(null);
        if (query == null) {
            log.warn("AiAnalysisSkippedEvent for unknown query {}", event.queryRequestId());
            return;
        }
        if (query.status() != QueryStatus.PENDING_AI) {
            log.warn("AiAnalysisSkippedEvent for query {} not in PENDING_AI (status={})",
                    query.id(), query.status());
            return;
        }
        var plan = reviewPlanLookupService.findForDatasource(query.datasourceId()).orElse(null);
        var context = buildContext(query, null, -1);
        if (applyRoutingPolicy(query, context, plan)) {
            return;
        }
        var nextStatus = decideNextStatusOnSkip(plan);
        queryRequestStateService.transitionTo(query.id(), QueryStatus.PENDING_AI, nextStatus);
        publishTerminalOrPending(query.id(), nextStatus);
    }

    @ApplicationModuleListener
    void onAiFailed(AiAnalysisFailedEvent event) {
        var query = queryRequestLookupService.findById(event.queryRequestId()).orElse(null);
        if (query == null) {
            log.warn("AiAnalysisFailedEvent for unknown query {}", event.queryRequestId());
            return;
        }
        if (query.status() != QueryStatus.PENDING_AI) {
            log.warn("AiAnalysisFailedEvent for query {} not in PENDING_AI (status={})",
                    query.id(), query.status());
            return;
        }
        queryRequestStateService.transitionTo(query.id(), QueryStatus.PENDING_AI,
                QueryStatus.PENDING_REVIEW);
        eventPublisher.publishEvent(new QueryReadyForReviewEvent(query.id()));
    }

    /**
     * Evaluate routing policies and, if one matches, persist the decision, transition the query, and
     * publish the matching event.
     *
     * @return {@code true} when a policy matched and handled the query; {@code false} to fall
     *         through to plan-based routing.
     */
    private boolean applyRoutingPolicy(QueryRequestSnapshot query, ConditionContext context,
                                       ReviewPlanSnapshot plan) {
        var match = routingPolicyEngine.evaluate(query.organizationId(), query.datasourceId(),
                context).orElse(null);
        if (match == null) {
            return false;
        }
        switch (match.action()) {
            case AUTO_APPROVE -> {
                routingDecisionService.applyDecision(query.id(), QueryStatus.APPROVED, match, null);
                eventPublisher.publishEvent(
                        new QueryAutoApprovedEvent(query.id(), match.policyId(), match.reason()));
            }
            case AUTO_REJECT -> {
                routingDecisionService.applyDecision(query.id(), QueryStatus.REJECTED, match, null);
                eventPublisher.publishEvent(
                        new QueryAutoRejectedEvent(query.id(), match.policyId(), match.reason()));
            }
            case REQUIRE_APPROVALS -> {
                int effective = effectiveForRequire(match);
                routingDecisionService.applyDecision(query.id(), QueryStatus.PENDING_REVIEW, match,
                        effective);
                eventPublisher.publishEvent(new QueryReadyForReviewEvent(query.id(),
                        match.policyId(), match.reason(), effective));
            }
            case ESCALATE -> {
                int effective = effectiveForEscalate(match, plan);
                routingDecisionService.applyDecision(query.id(), QueryStatus.PENDING_REVIEW, match,
                        effective);
                eventPublisher.publishEvent(new QueryReadyForReviewEvent(query.id(),
                        match.policyId(), match.reason(), effective));
            }
        }
        log.info("Query {} routed by policy {} -> {}", query.id(), match.policyId(), match.action());
        return true;
    }

    private static int effectiveForRequire(RoutingMatch match) {
        return match.requiredApprovals() != null ? match.requiredApprovals() : 1;
    }

    private static int effectiveForEscalate(RoutingMatch match, ReviewPlanSnapshot plan) {
        int basis = plan != null ? plan.minApprovalsRequired() : 1;
        int delta = match.requiredApprovals() != null ? match.requiredApprovals() : 1;
        return basis + delta;
    }

    private ConditionContext buildContext(QueryRequestSnapshot query, RiskLevel riskLevel,
                                          int riskScore) {
        var role = userQueryService.findById(query.submittedByUserId())
                .map(UserView::role)
                .orElse(null);
        var groupIds = Set.copyOf(userGroupService.findGroupIdsForUser(query.submittedByUserId()));
        Set<String> referencedTables = Set.of();
        boolean hasWhere = false;
        boolean hasLimit = false;
        boolean transactional = query.transactional();
        try {
            var parsed = sqlParserService.parse(query.sqlText());
            referencedTables = parsed.referencedTables();
            hasWhere = parsed.hasWhereClause();
            hasLimit = parsed.hasLimitClause();
            transactional = parsed.transactional();
        } catch (RuntimeException ex) {
            log.warn("Routing: failed to re-parse SQL for query {}; table/clause signals unavailable",
                    query.id());
        }
        Integer minutesSinceLastApproval = queryRequestLookupService
                .findLastApprovalInstant(query.organizationId(), query.submittedByUserId(),
                        query.datasourceId(), query.id())
                .map(last -> (int) Math.max(0, Duration.between(last, clock.instant()).toMinutes()))
                .orElse(null);
        boolean anomalyActive = behaviorAnomalyLookupService.hasActiveAnomaly(
                query.organizationId(), query.submittedByUserId(), query.datasourceId());
        return new ConditionContext(query.queryType(), referencedTables, riskLevel, riskScore, role,
                groupIds, LocalDateTime.now(clock), hasWhere, hasLimit, transactional,
                query.submittedIp(), query.submittedUserAgent(), query.ciCdOrigin(),
                minutesSinceLastApproval, anomalyActive);
    }

    private QueryStatus decideNextStatus(ReviewPlanSnapshot plan, QueryRequestSnapshot query,
                                         RiskLevel riskLevel) {
        if (plan == null) {
            log.info("Query {} has no review plan; routing to PENDING_REVIEW", query.id());
            return QueryStatus.PENDING_REVIEW;
        }
        if (!plan.requiresHumanApproval()) {
            return QueryStatus.APPROVED;
        }
        if (canFastPathApprove(plan, query.queryType(), riskLevel)) {
            return QueryStatus.APPROVED;
        }
        return QueryStatus.PENDING_REVIEW;
    }

    private static QueryStatus decideNextStatusOnSkip(ReviewPlanSnapshot plan) {
        if (plan != null && !plan.requiresHumanApproval()) {
            return QueryStatus.APPROVED;
        }
        return QueryStatus.PENDING_REVIEW;
    }

    private static boolean canFastPathApprove(ReviewPlanSnapshot plan, QueryType queryType,
                                              RiskLevel riskLevel) {
        return plan.autoApproveReads()
                && queryType == QueryType.SELECT
                && (riskLevel == RiskLevel.LOW || riskLevel == RiskLevel.MEDIUM);
    }

    private void publishTerminalOrPending(UUID queryRequestId, QueryStatus next) {
        if (next == QueryStatus.APPROVED) {
            eventPublisher.publishEvent(new QueryAutoApprovedEvent(queryRequestId));
        } else {
            eventPublisher.publishEvent(new QueryReadyForReviewEvent(queryRequestId));
        }
    }
}
