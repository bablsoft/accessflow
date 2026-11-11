package com.bablsoft.accessflow.notifications.internal.strategy;

import com.bablsoft.accessflow.notifications.api.NotificationEventType;
import com.bablsoft.accessflow.notifications.internal.NotificationContext;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DeploymentVerificationTextTest {

    @Test
    void appliesOnlyToTheVerificationFailedEvent() {
        assertThat(DeploymentVerificationText.applies(
                ctx(NotificationEventType.DEPLOYMENT_VERIFICATION_FAILED, null))).isTrue();
        assertThat(DeploymentVerificationText.applies(
                ctx(NotificationEventType.DEPLOYMENT_OUTCOME_FAILED, null))).isFalse();
    }

    @Test
    void detailFallsBackToADashAndIsBounded() {
        var type = NotificationEventType.DEPLOYMENT_VERIFICATION_FAILED;
        assertThat(DeploymentVerificationText.detail(ctx(type, null))).isEqualTo("—");
        assertThat(DeploymentVerificationText.detail(ctx(type, "  "))).isEqualTo("—");
        assertThat(DeploymentVerificationText.detail(ctx(type, "health: 3/3 failed samples")))
                .isEqualTo("health: 3/3 failed samples");
        assertThat(DeploymentVerificationText.detail(ctx(type,
                "x".repeat(SchemaChangeText.ERROR_MAX_LENGTH + 5))))
                .hasSize(SchemaChangeText.ERROR_MAX_LENGTH);
    }

    @Test
    void recommendationNeverClaimsARollbackHappened() {
        assertThat(DeploymentVerificationText.RECOMMENDATION)
                .contains("still running")
                .contains("did not roll it back")
                .contains("ROLLED_BACK");
    }

    private static NotificationContext ctx(NotificationEventType type, String reason) {
        var ctx = mock(NotificationContext.class);
        when(ctx.eventType()).thenReturn(type);
        when(ctx.deploymentDecisionReason()).thenReturn(reason);
        return ctx;
    }
}
