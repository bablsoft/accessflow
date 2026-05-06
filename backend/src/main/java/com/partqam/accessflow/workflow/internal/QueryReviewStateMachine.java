package com.partqam.accessflow.workflow.internal;

import com.partqam.accessflow.core.api.QueryRequestLookupService;
import com.partqam.accessflow.core.api.QueryRequestSnapshot;
import com.partqam.accessflow.core.api.QueryRequestStateService;
import com.partqam.accessflow.core.api.QueryStatus;
import com.partqam.accessflow.core.api.QueryType;
import com.partqam.accessflow.core.api.ReviewPlanLookupService;
import com.partqam.accessflow.core.api.ReviewPlanSnapshot;
import com.partqam.accessflow.core.api.RiskLevel;
import com.partqam.accessflow.core.events.AiAnalysisCompletedEvent;
import com.partqam.accessflow.core.events.AiAnalysisFailedEvent;
import com.partqam.accessflow.core.events.QueryAutoApprovedEvent;
import com.partqam.accessflow.core.events.QueryReadyForReviewEvent;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Drives the {@code PENDING_AI → PENDING_REVIEW | APPROVED} transition by listening to the AI
 * module's completion / failure events. AI failure unconditionally lands in
 * {@code PENDING_REVIEW} so a human can inspect the query — auto-approve is a positive-signal
 * fast-path; a missing AI result is not symmetric to a low-risk one.
 */
@Component
@RequiredArgsConstructor
class QueryReviewStateMachine {

    private static final Logger log = LoggerFactory.getLogger(QueryReviewStateMachine.class);

    private final QueryRequestLookupService queryRequestLookupService;
    private final ReviewPlanLookupService reviewPlanLookupService;
    private final QueryRequestStateService queryRequestStateService;
    private final ApplicationEventPublisher eventPublisher;

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
        var nextStatus = decideNextStatus(query, event.riskLevel());
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

    private QueryStatus decideNextStatus(QueryRequestSnapshot query, RiskLevel riskLevel) {
        var plan = reviewPlanLookupService.findForDatasource(query.datasourceId()).orElse(null);
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
