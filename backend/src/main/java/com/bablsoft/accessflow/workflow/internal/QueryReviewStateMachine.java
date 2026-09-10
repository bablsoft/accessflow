package com.bablsoft.accessflow.workflow.internal;

import com.bablsoft.accessflow.core.api.QueryRequestLookupService;
import com.bablsoft.accessflow.core.api.QueryRequestSnapshot;
import com.bablsoft.accessflow.core.api.QueryRequestStateService;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.events.AiAnalysisCompletedEvent;
import com.bablsoft.accessflow.core.events.AiAnalysisFailedEvent;
import com.bablsoft.accessflow.core.events.AiAnalysisSkippedEvent;
import com.bablsoft.accessflow.core.events.QueryAutoApprovedEvent;
import com.bablsoft.accessflow.core.events.QueryAutoRejectedEvent;
import com.bablsoft.accessflow.core.events.QueryReadyForReviewEvent;
import com.bablsoft.accessflow.workflow.api.AiOutcome;
import com.bablsoft.accessflow.workflow.internal.routing.RoutingDecisionService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.MessageSource;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Locale;
import java.util.UUID;

/**
 * Applies the {@code PENDING_AI → PENDING_REVIEW | APPROVED | REJECTED} transition on the AI module's
 * completion / failure / skipped events.
 *
 * <p>Deciding lives in {@link QueryDecisionEvaluator}; this class only carries the decision out —
 * persisting the routing decision, transitioning the query and publishing the matching event. The
 * split (issue AF-859) is what lets the access simulator replay a hypothetical request through the
 * identical rules without any of these side effects.
 *
 * <p>The chain itself is unchanged: the {@code RoutingPolicyEngine} runs first and the first enabled
 * policy by ascending priority whose typed condition matches decides — {@code AUTO_APPROVE} /
 * {@code AUTO_REJECT} short-circuit, {@code REQUIRE_APPROVALS} / {@code ESCALATE} force human review
 * with an effective approval-count override persisted on {@code routing_decision}. On no match, the
 * grant-covered fast-path (#582) runs next, and only then does the query fall through to the
 * datasource's review plan.
 *
 * <p>AI failure unconditionally lands in {@code PENDING_REVIEW} so a human can inspect the query. The
 * skipped path (datasource has {@code ai_analysis_enabled = false}) runs routing with no risk signal
 * — risk-based conditions evaluate to {@code false} — and otherwise respects
 * {@code plan.requires_human_approval}, never short-circuiting via {@code auto_approve_reads}, since
 * the SELECT/low-risk fast-path needs an AI risk signal.
 */
@Component
@RequiredArgsConstructor
class QueryReviewStateMachine {

    private static final Logger log = LoggerFactory.getLogger(QueryReviewStateMachine.class);

    private final QueryRequestLookupService queryRequestLookupService;
    private final QueryDecisionEvaluator queryDecisionEvaluator;
    private final QueryRequestStateService queryRequestStateService;
    private final RoutingDecisionService routingDecisionService;
    private final MessageSource messageSource;
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
        var query = load(event.queryRequestId(), "AiAnalysisCompletedEvent");
        if (query != null) {
            apply(query, queryDecisionEvaluator.evaluate(query, AiOutcome.COMPLETED,
                    event.riskLevel(), event.riskScore(), clock));
        }
    }

    @ApplicationModuleListener
    void onAiSkipped(AiAnalysisSkippedEvent event) {
        var query = load(event.queryRequestId(), "AiAnalysisSkippedEvent");
        if (query != null) {
            apply(query, queryDecisionEvaluator.evaluate(query, AiOutcome.SKIPPED, null, -1, clock));
        }
    }

    @ApplicationModuleListener
    void onAiFailed(AiAnalysisFailedEvent event) {
        var query = load(event.queryRequestId(), "AiAnalysisFailedEvent");
        if (query != null) {
            apply(query, queryDecisionEvaluator.evaluate(query, AiOutcome.FAILED, null, -1, clock));
        }
    }

    /** @return the query when it is present and still awaiting a decision, {@code null} otherwise. */
    private QueryRequestSnapshot load(UUID queryRequestId, String eventName) {
        var query = queryRequestLookupService.findById(queryRequestId).orElse(null);
        if (query == null) {
            log.warn("{} for unknown query {}", eventName, queryRequestId);
            return null;
        }
        if (query.status() != QueryStatus.PENDING_AI) {
            log.warn("{} for query {} not in PENDING_AI (status={})", eventName, query.id(),
                    query.status());
            return null;
        }
        return query;
    }

    /**
     * Carry out the decision. Every branch is one persistence call plus one event; the routing
     * branches go through {@link RoutingDecisionService#applyDecision}, which writes the
     * {@code routing_decision} row and transitions the query in a single transaction.
     */
    private void apply(QueryRequestSnapshot query, QueryDecision decision) {
        var match = decision.routingMatch();
        if (match != null) {
            log.info("Query {} routed by policy {} -> {}", query.id(), match.policyId(),
                    match.action());
        } else if (decision.kind() == QueryDecisionKind.GRANT_FAST_PATH) {
            log.info("Query {} auto-approved under access grant {}", query.id(), decision.grantId());
        }
        switch (decision.kind()) {
            case ROUTING_AUTO_APPROVE -> {
                routingDecisionService.applyDecision(query.id(), QueryStatus.APPROVED, match, null);
                eventPublisher.publishEvent(
                        new QueryAutoApprovedEvent(query.id(), match.policyId(), match.reason()));
            }
            case ROUTING_AUTO_REJECT -> {
                routingDecisionService.applyDecision(query.id(), QueryStatus.REJECTED, match, null);
                eventPublisher.publishEvent(
                        new QueryAutoRejectedEvent(query.id(), match.policyId(), match.reason()));
            }
            case ROUTING_REQUIRE_APPROVALS, ROUTING_ESCALATE -> {
                routingDecisionService.applyDecision(query.id(), QueryStatus.PENDING_REVIEW, match,
                        decision.effectiveApprovals());
                eventPublisher.publishEvent(new QueryReadyForReviewEvent(query.id(),
                        match.policyId(), match.reason(), decision.effectiveApprovals()));
            }
            // no default: every kind is handled, and the compiler enforces that on a new value
            case GRANT_FAST_PATH -> {
                queryRequestStateService.approveByAccessGrant(query.id(), decision.grantId());
                eventPublisher.publishEvent(new QueryAutoApprovedEvent(query.id(), null,
                        grantReason(decision), decision.grantId(), decision.grantApproverEmail()));
            }
            case PLAN_APPROVED, PLAN_PENDING_REVIEW, AI_FAILED_PENDING_REVIEW -> {
                queryRequestStateService.transitionTo(query.id(), QueryStatus.PENDING_AI,
                        decision.nextStatus());
                eventPublisher.publishEvent(decision.nextStatus() == QueryStatus.APPROVED
                        ? new QueryAutoApprovedEvent(query.id())
                        : new QueryReadyForReviewEvent(query.id()));
            }
        }
    }

    /**
     * Rendered here rather than in the evaluator: there is no request locale on this asynchronous
     * path, so the reason falls back to the server default, and the evaluator stays free of
     * localization concerns it cannot resolve.
     */
    private String grantReason(QueryDecision decision) {
        var approver = decision.grantApproverEmail() != null ? decision.grantApproverEmail() : "-";
        return messageSource.getMessage("workflow.grant_pre_approval.reason",
                new Object[]{decision.grantId(), approver}, Locale.getDefault());
    }
}
