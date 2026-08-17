package com.bablsoft.accessflow.notifications.internal;

import com.bablsoft.accessflow.attestation.events.AttestationCampaignOpenedEvent;
import com.bablsoft.accessflow.core.api.RiskLevel;
import com.bablsoft.accessflow.core.events.QueryReviewEscalatedEvent;
import com.bablsoft.accessflow.core.events.QueryReviewNudgedEvent;
import com.bablsoft.accessflow.core.events.AiAnalysisCompletedEvent;
import com.bablsoft.accessflow.access.events.GrantStaleEvent;
import com.bablsoft.accessflow.core.events.AnomalyDetectedEvent;
import com.bablsoft.accessflow.core.events.QueryAutoApprovedEvent;
import com.bablsoft.accessflow.core.events.QueryAutoRejectedEvent;
import com.bablsoft.accessflow.core.events.QueryReadyForReviewEvent;
import com.bablsoft.accessflow.core.events.QueryTimedOutEvent;
import com.bablsoft.accessflow.dashboard.events.WeeklyDigestReadyEvent;
import com.bablsoft.accessflow.lifecycle.events.ErasureRequestApprovedEvent;
import com.bablsoft.accessflow.notifications.api.NotificationEventType;
import com.bablsoft.accessflow.workflow.events.BreakGlassExecutedEvent;
import com.bablsoft.accessflow.workflow.events.QueryApprovedEvent;
import com.bablsoft.accessflow.workflow.events.QueryExecutedEvent;
import com.bablsoft.accessflow.workflow.events.QueryRejectedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
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
        // A populated matched-policy pair means a routing policy (ESCALATE / REQUIRE_APPROVALS,
        // AF-446) raised the approval bar — surface it as a distinct escalation event (AF-453).
        if (event.matchedPolicyId() != null && event.effectiveMinApprovals() != null) {
            safeDispatch(NotificationEventType.QUERY_ESCALATED, event.queryRequestId(),
                    null, null, null);
        }
    }

    @ApplicationModuleListener
    void onReviewEscalated(QueryReviewEscalatedEvent event) {
        safeDispatch(NotificationEventType.REVIEW_ESCALATED, event.queryRequestId(),
                null, null, null);
    }

    @ApplicationModuleListener
    void onReviewNudged(QueryReviewNudgedEvent event) {
        safeDispatch(NotificationEventType.REVIEW_NUDGE, event.queryRequestId(),
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

    /**
     * Result delivery for recurring occurrences (#627). A plain {@link EventListener}, NOT an
     * {@code @ApplicationModuleListener}: {@link QueryExecutedEvent} is published outside any
     * transaction, so an AFTER_COMMIT listener would silently never fire (the
     * {@code QuerySnapshotListener} Javadoc documents the same trap). Only occurrence rows
     * ({@code recurringParentId != null}) are dispatched — one-off executions notify nobody,
     * matching the deferred row in docs/08-notifications.md.
     */
    @EventListener
    void onQueryExecuted(QueryExecutedEvent event) {
        if (event.recurringParentId() == null) {
            return;
        }
        try {
            dispatcher.dispatchQueryExecuted(event.queryRequestId(), event.finalStatus(),
                    event.rowsAffected(), event.durationMs());
        } catch (RuntimeException ex) {
            log.error("Notification dispatch failed for QUERY_EXECUTED on query {}",
                    event.queryRequestId(), ex);
        }
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
    void onGrantStale(GrantStaleEvent event) {
        try {
            dispatcher.dispatchGrantStale(event);
        } catch (RuntimeException ex) {
            log.error("Notification dispatch failed for stale grant summary {}",
                    event.summaryId(), ex);
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

    @ApplicationModuleListener
    void onErasureApproved(ErasureRequestApprovedEvent event) {
        try {
            dispatcher.dispatchErasureApproved(event.organizationId(), event.requestedBy());
        } catch (RuntimeException ex) {
            log.error("Notification dispatch failed for approved erasure {}", event.requestId(), ex);
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
