package com.bablsoft.accessflow.notifications.internal;

import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.deploygov.api.DeploymentOutcome;
import com.bablsoft.accessflow.deploygov.events.DeploymentBreakGlassExecutedEvent;
import com.bablsoft.accessflow.deploygov.events.DeploymentDecidedEvent;
import com.bablsoft.accessflow.deploygov.events.DeploymentOutcomeReportedEvent;
import com.bablsoft.accessflow.deploygov.events.DeploymentStatusChangedEvent;
import com.bablsoft.accessflow.notifications.api.NotificationEventType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class DeploymentNotificationListenerTest {

    @Mock private NotificationDispatcher dispatcher;
    private DeploymentNotificationListener listener;

    private final UUID requestId = UUID.randomUUID();
    private final UUID orgId = UUID.randomUUID();
    private final UUID pipelineId = UUID.randomUUID();
    private final UUID submitterId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        listener = new DeploymentNotificationListener(dispatcher);
    }

    @Test
    void pendingReviewTransitionNotifiesSubmittedEvent() {
        listener.onStatusChanged(new DeploymentStatusChangedEvent(requestId, submitterId,
                QueryStatus.PENDING_AI, QueryStatus.PENDING_REVIEW));
        verify(dispatcher).dispatchDeployment(NotificationEventType.DEPLOYMENT_SUBMITTED,
                requestId, null, null);
    }

    /** Auto-decided deployments never reach PENDING_REVIEW, so reviewers are never pinged. */
    @Test
    void otherStatusTransitionsAreNotNotified() {
        for (var status : new QueryStatus[]{QueryStatus.PENDING_AI, QueryStatus.APPROVED,
                QueryStatus.REJECTED, QueryStatus.EXECUTED, QueryStatus.FAILED,
                QueryStatus.TIMED_OUT, QueryStatus.CANCELLED}) {
            listener.onStatusChanged(new DeploymentStatusChangedEvent(requestId, submitterId,
                    QueryStatus.PENDING_AI, status));
        }
        verify(dispatcher, never()).dispatchDeployment(any(), any(), any(), any());
    }

    @Test
    void approvedMapsToApprovedEvent() {
        listener.onDecided(new DeploymentDecidedEvent(requestId, QueryStatus.APPROVED, null));
        verify(dispatcher).dispatchDeployment(NotificationEventType.DEPLOYMENT_APPROVED,
                requestId, null, null);
    }

    @Test
    void rejectedMapsToRejectedEventWithTheReason() {
        listener.onDecided(new DeploymentDecidedEvent(requestId, QueryStatus.REJECTED,
                "freeze:" + UUID.randomUUID()));
        verify(dispatcher).dispatchDeployment(
                org.mockito.ArgumentMatchers.eq(NotificationEventType.DEPLOYMENT_REJECTED),
                org.mockito.ArgumentMatchers.eq(requestId),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.startsWith("freeze:"));
    }

    /** A timeout is a rejection from the submitter's perspective; the reason rides along. */
    @Test
    void timedOutMapsToRejectedEventWithTheTimeoutReason() {
        listener.onDecided(new DeploymentDecidedEvent(requestId, QueryStatus.TIMED_OUT,
                "review_timeout"));
        verify(dispatcher).dispatchDeployment(NotificationEventType.DEPLOYMENT_REJECTED,
                requestId, null, "review_timeout");
    }

    @Test
    void unmappedDecisionStatusesAreNotNotified() {
        listener.onDecided(new DeploymentDecidedEvent(requestId, QueryStatus.CANCELLED, null));
        verify(dispatcher, never()).dispatchDeployment(any(), any(), any(), any());
    }

    @Test
    void failedOutcomeNotifiesTheGrantingApprovers() {
        listener.onOutcomeReported(new DeploymentOutcomeReportedEvent(orgId, requestId,
                pipelineId, DeploymentOutcome.FAILED, "crashed"));
        verify(dispatcher).dispatchDeployment(NotificationEventType.DEPLOYMENT_OUTCOME_FAILED,
                requestId, DeploymentOutcome.FAILED, null);
    }

    @Test
    void rolledBackOutcomeNotifiesTheGrantingApprovers() {
        listener.onOutcomeReported(new DeploymentOutcomeReportedEvent(orgId, requestId,
                pipelineId, DeploymentOutcome.ROLLED_BACK, "regression"));
        verify(dispatcher).dispatchDeployment(NotificationEventType.DEPLOYMENT_OUTCOME_FAILED,
                requestId, DeploymentOutcome.ROLLED_BACK, null);
    }

    @Test
    void succeededOutcomeIsNotNotified() {
        listener.onOutcomeReported(new DeploymentOutcomeReportedEvent(orgId, requestId,
                pipelineId, DeploymentOutcome.SUCCEEDED, null));
        verify(dispatcher, never()).dispatchDeployment(any(), any(), any(), any());
    }

    @Test
    void breakGlassExecutionNotifiesAdmins() {
        listener.onBreakGlassExecuted(new DeploymentBreakGlassExecutedEvent(orgId, requestId,
                pipelineId, submitterId, "incident 42"));
        verify(dispatcher).dispatchDeployment(
                NotificationEventType.DEPLOYMENT_BREAK_GLASS_EXECUTED, requestId, null, null);
    }

    /**
     * Break-glass publishes no DeploymentDecidedEvent (the force-approve happens inside
     * breakGlassApprove without one), so the submitter gets no APPROVED self-notification. Pinned
     * here so a future refactor that starts publishing DecidedEvent on that path trips a test.
     */
    @Test
    void breakGlassDoesNotAlsoNotifyTheSubmitterOfApproval() {
        listener.onBreakGlassExecuted(new DeploymentBreakGlassExecutedEvent(orgId, requestId,
                pipelineId, submitterId, "incident 42"));
        verify(dispatcher, never()).dispatchDeployment(
                org.mockito.ArgumentMatchers.eq(NotificationEventType.DEPLOYMENT_APPROVED),
                any(), any(), any());
    }
}
