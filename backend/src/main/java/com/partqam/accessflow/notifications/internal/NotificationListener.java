package com.partqam.accessflow.notifications.internal;

import com.partqam.accessflow.core.api.RiskLevel;
import com.partqam.accessflow.core.events.AiAnalysisCompletedEvent;
import com.partqam.accessflow.notifications.api.NotificationEventType;
import com.partqam.accessflow.workflow.events.QueryApprovedEvent;
import com.partqam.accessflow.core.events.QueryAutoApprovedEvent;
import com.partqam.accessflow.core.events.QueryReadyForReviewEvent;
import com.partqam.accessflow.workflow.events.QueryRejectedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Bridges workflow / AI events to {@link NotificationDispatcher}. Each handler swallows runtime
 * failures so notification problems can never affect query workflow state — the workflow module
 * already committed before these listeners fire.
 */
@Component
@RequiredArgsConstructor
@Slf4j
class NotificationListener {

    private final NotificationDispatcher dispatcher;

    @ApplicationModuleListener
    void onQueryReadyForReview(QueryReadyForReviewEvent event) {
        safeDispatch(NotificationEventType.QUERY_SUBMITTED, event.queryRequestId(), null, null);
    }

    @ApplicationModuleListener
    void onQueryApproved(QueryApprovedEvent event) {
        safeDispatch(NotificationEventType.QUERY_APPROVED, event.queryRequestId(),
                event.reviewerId(), null);
    }

    @ApplicationModuleListener
    void onQueryAutoApproved(QueryAutoApprovedEvent event) {
        safeDispatch(NotificationEventType.QUERY_APPROVED, event.queryRequestId(), null, null);
    }

    @ApplicationModuleListener
    void onQueryRejected(QueryRejectedEvent event) {
        safeDispatch(NotificationEventType.QUERY_REJECTED, event.queryRequestId(),
                event.reviewerId(), null);
    }

    @ApplicationModuleListener
    void onAiCompleted(AiAnalysisCompletedEvent event) {
        if (event.riskLevel() != RiskLevel.CRITICAL) {
            return;
        }
        safeDispatch(NotificationEventType.AI_HIGH_RISK, event.queryRequestId(), null, null);
    }

    private void safeDispatch(NotificationEventType type, UUID queryRequestId,
                              UUID reviewerUserId, String reviewerComment) {
        try {
            dispatcher.dispatch(type, queryRequestId, reviewerUserId, reviewerComment);
        } catch (RuntimeException ex) {
            log.error("Notification dispatch failed for event {} on query {}",
                    type, queryRequestId, ex);
        }
    }
}
