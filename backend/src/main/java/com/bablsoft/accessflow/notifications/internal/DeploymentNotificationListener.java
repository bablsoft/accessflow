package com.bablsoft.accessflow.notifications.internal;

import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.deploygov.api.DeploymentOutcome;
import com.bablsoft.accessflow.deploygov.events.DeploymentBreakGlassExecutedEvent;
import com.bablsoft.accessflow.deploygov.events.DeploymentDecidedEvent;
import com.bablsoft.accessflow.deploygov.events.DeploymentOutcomeReportedEvent;
import com.bablsoft.accessflow.deploygov.events.DeploymentStatusChangedEvent;
import com.bablsoft.accessflow.notifications.api.NotificationEventType;
import lombok.RequiredArgsConstructor;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Fans out deployment-governance notifications (#695, epic #682). {@code DEPLOYMENT_SUBMITTED}
 * deliberately fires on the {@code PENDING_REVIEW} transition rather than on
 * {@code DeploymentSubmittedEvent}: reviewers are only alerted when human action is needed, so a
 * deployment that a routing policy auto-decides never pings them. A decided deployment alerts the
 * submitter ({@code TIMED_OUT} folds into {@code DEPLOYMENT_REJECTED} with its
 * {@code review_timeout} reason); a {@code FAILED} or {@code ROLLED_BACK} outcome alerts the
 * reviewers who approved it; a break-glass release alerts every org admin. Break-glass publishes no
 * {@code DeploymentDecidedEvent}, so the submitter never self-notifies on that path. Delivery is
 * best-effort and never blocks the deployment flow.
 */
@Component
@RequiredArgsConstructor
class DeploymentNotificationListener {

    private final NotificationDispatcher dispatcher;

    @ApplicationModuleListener
    void onStatusChanged(DeploymentStatusChangedEvent event) {
        if (event.newStatus() == QueryStatus.PENDING_REVIEW) {
            dispatcher.dispatchDeployment(NotificationEventType.DEPLOYMENT_SUBMITTED,
                    event.deploymentRequestId(), null, null);
        }
    }

    @ApplicationModuleListener
    void onDecided(DeploymentDecidedEvent event) {
        var type = mapStatus(event.status());
        if (type != null) {
            dispatcher.dispatchDeployment(type, event.deploymentRequestId(), null, event.reason());
        }
    }

    @ApplicationModuleListener
    void onOutcomeReported(DeploymentOutcomeReportedEvent event) {
        if (event.outcome() == DeploymentOutcome.FAILED
                || event.outcome() == DeploymentOutcome.ROLLED_BACK) {
            dispatcher.dispatchDeployment(NotificationEventType.DEPLOYMENT_OUTCOME_FAILED,
                    event.deploymentRequestId(), event.outcome(), null);
        }
    }

    @ApplicationModuleListener
    void onBreakGlassExecuted(DeploymentBreakGlassExecutedEvent event) {
        dispatcher.dispatchDeployment(NotificationEventType.DEPLOYMENT_BREAK_GLASS_EXECUTED,
                event.deploymentRequestId(), null, null);
    }

    private static NotificationEventType mapStatus(QueryStatus status) {
        return switch (status) {
            case APPROVED -> NotificationEventType.DEPLOYMENT_APPROVED;
            // A timeout is a rejection from the submitter's perspective; the reason
            // ("review_timeout") rides along so renderers can say why.
            case REJECTED, TIMED_OUT -> NotificationEventType.DEPLOYMENT_REJECTED;
            default -> null;
        };
    }
}
