package com.bablsoft.accessflow.notifications.internal.strategy;

import com.bablsoft.accessflow.notifications.api.NotificationEventType;
import com.bablsoft.accessflow.notifications.internal.NotificationContext;

/**
 * Shared chat rendering for {@code DEPLOYMENT_VERIFICATION_FAILED} (#1026). The wording is a
 * recommendation: AccessFlow never rolls back, so no channel may imply that it did.
 */
final class DeploymentVerificationText {

    static final String TITLE = "🚨 Deployment Verification Failed";
    static final String RECOMMENDATION = "The release is still running — AccessFlow did not roll it "
            + "back. Decide whether to roll back, then report ROLLED_BACK.";

    private DeploymentVerificationText() {
    }

    static boolean applies(NotificationContext ctx) {
        return ctx.eventType() == NotificationEventType.DEPLOYMENT_VERIFICATION_FAILED;
    }

    /** The breached check and its sample count, bounded like every other chat field. */
    static String detail(NotificationContext ctx) {
        var detail = ctx.deploymentDecisionReason();
        return detail == null || detail.isBlank() ? "—" : SchemaChangeText.truncate(detail);
    }
}
