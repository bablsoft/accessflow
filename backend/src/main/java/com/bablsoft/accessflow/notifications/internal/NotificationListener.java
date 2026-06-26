package com.bablsoft.accessflow.notifications.internal;

import com.bablsoft.accessflow.attestation.events.AttestationCampaignOpenedEvent;
import com.bablsoft.accessflow.core.api.RiskLevel;
import com.bablsoft.accessflow.core.events.AiAnalysisCompletedEvent;
import com.bablsoft.accessflow.core.events.AnomalyDetectedEvent;
import com.bablsoft.accessflow.core.events.QueryAutoApprovedEvent;
import com.bablsoft.accessflow.core.events.QueryAutoRejectedEvent;
import com.bablsoft.accessflow.core.events.QueryReadyForReviewEvent;
import com.bablsoft.accessflow.core.events.QueryTimedOutEvent;
import com.bablsoft.accessflow.dashboard.events.WeeklyDigestReadyEvent;
import com.bablsoft.accessflow.notifications.api.NotificationEventType;
import com.bablsoft.accessflow.workflow.events.BreakGlassExecutedEvent;
import com.bablsoft.accessflow.workflow.events.QueryApprovedEvent;
import com.bablsoft.accessflow.workflow.events.QueryRejectedEvent;
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
        safeDispatch(NotificationEventType.QUERY_SUBMITTED, event.queryRequestId(),
                null, null, null);
    }

    @ApplicationModuleListener
    void onQueryApproved(QueryApprovedEvent event) {
        safeDispatch(NotificationEventType.QUERY_APPROVED, event.queryRequestId(),
                event.reviewerId(), null, null);
    }

    @ApplicationModuleListener
    void onQueryAutoApproved(QueryAutoApprovedEvent event) {
        safeDispatch(NotificationEventType.QUERY_APPROVED, event.queryRequestId(),
                null, null, null);
    }

    @ApplicationModuleListener
    void onQueryRejected(QueryRejectedEvent event) {
        safeDispatch(NotificationEventType.QUERY_REJECTED, event.queryRequestId(),
                event.reviewerId(), null, null);
    }

    @ApplicationModuleListener
    void onQueryAutoRejected(QueryAutoRejectedEvent event) {
        safeDispatch(NotificationEventType.QUERY_REJECTED, event.queryRequestId(),
                null, null, null);
    }

    @ApplicationModuleListener
    void onQueryTimedOut(QueryTimedOutEvent event) {
        safeDispatch(NotificationEventType.REVIEW_TIMEOUT, event.queryRequestId(),
                null, null, event.approvalTimeoutHours());
    }

    @ApplicationModuleListener
    void onAiCompleted(AiAnalysisCompletedEvent event) {
        if (event.riskLevel() != RiskLevel.CRITICAL) {
            return;
        }
        safeDispatch(NotificationEventType.AI_HIGH_RISK, event.queryRequestId(),
                null, null, null);
    }

    @ApplicationModuleListener
    void onBreakGlassExecuted(BreakGlassExecutedEvent event) {
        safeDispatch(NotificationEventType.BREAK_GLASS_EXECUTED, event.queryRequestId(),
                null, null, null);
    }

    @ApplicationModuleListener
    void onAnomalyDetected(AnomalyDetectedEvent event) {
        try {
            dispatcher.dispatchAnomaly(event.anomalyId(), event.organizationId());
        } catch (RuntimeException ex) {
            log.error("Notification dispatch failed for anomaly {}", event.anomalyId(), ex);
        }
    }

    @ApplicationModuleListener
    void onWeeklyDigestReady(WeeklyDigestReadyEvent event) {
        try {
            dispatcher.dispatchWeeklyDigest(event);
        } catch (RuntimeException ex) {
            log.error("Notification dispatch failed for weekly digest of user {}", event.userId(), ex);
        }
    }

    @ApplicationModuleListener
    void onAttestationCampaignOpened(AttestationCampaignOpenedEvent event) {
        try {
            dispatcher.dispatchAttestationCampaignOpened(event.campaignId(),
                    event.organizationId());
        } catch (RuntimeException ex) {
            log.error("Notification dispatch failed for attestation campaign {}",
                    event.campaignId(), ex);
        }
    }

    private void safeDispatch(NotificationEventType type, UUID queryRequestId,
                              UUID reviewerUserId, String reviewerComment,
                              Integer approvalTimeoutHours) {
        try {
            dispatcher.dispatch(type, queryRequestId, reviewerUserId, reviewerComment,
                    approvalTimeoutHours);
        } catch (RuntimeException ex) {
            log.error("Notification dispatch failed for event {} on query {}",
                    type, queryRequestId, ex);
        }
    }
}
