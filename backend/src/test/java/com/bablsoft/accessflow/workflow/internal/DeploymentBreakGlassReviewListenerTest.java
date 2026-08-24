package com.bablsoft.accessflow.workflow.internal;

import com.bablsoft.accessflow.deploygov.events.DeploymentBreakGlassExecutedEvent;
import com.bablsoft.accessflow.workflow.api.BreakGlassService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class DeploymentBreakGlassReviewListenerTest {

    @Mock
    private BreakGlassService breakGlassService;

    @Test
    void opensRetroReviewFromEvent() {
        var listener = new DeploymentBreakGlassReviewListener(breakGlassService);
        var orgId = UUID.randomUUID();
        var deploymentRequestId = UUID.randomUUID();
        var pipelineId = UUID.randomUUID();
        var submitterId = UUID.randomUUID();

        listener.onDeploymentBreakGlassExecuted(new DeploymentBreakGlassExecutedEvent(
                orgId, deploymentRequestId, pipelineId, submitterId, "prod is down"));

        var captor = ArgumentCaptor.forClass(BreakGlassService.DeploymentBreakGlassReview.class);
        verify(breakGlassService).openDeploymentBreakGlassReview(captor.capture());
        assertThat(captor.getValue().organizationId()).isEqualTo(orgId);
        assertThat(captor.getValue().deploymentRequestId()).isEqualTo(deploymentRequestId);
        assertThat(captor.getValue().pipelineId()).isEqualTo(pipelineId);
        assertThat(captor.getValue().submitterUserId()).isEqualTo(submitterId);
        assertThat(captor.getValue().justification()).isEqualTo("prod is down");
    }
}
