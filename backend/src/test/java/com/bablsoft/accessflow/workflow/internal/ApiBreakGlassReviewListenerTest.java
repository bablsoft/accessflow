package com.bablsoft.accessflow.workflow.internal;

import com.bablsoft.accessflow.apigov.events.ApiBreakGlassExecutedEvent;
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
class ApiBreakGlassReviewListenerTest {

    @Mock
    private BreakGlassService breakGlassService;

    @Test
    void opensRetroReviewFromEvent() {
        var listener = new ApiBreakGlassReviewListener(breakGlassService);
        var orgId = UUID.randomUUID();
        var apiRequestId = UUID.randomUUID();
        var connectorId = UUID.randomUUID();
        var submitterId = UUID.randomUUID();

        listener.onApiBreakGlassExecuted(new ApiBreakGlassExecutedEvent(
                orgId, apiRequestId, connectorId, submitterId, "prod is down"));

        var captor = ArgumentCaptor.forClass(BreakGlassService.ApiBreakGlassReview.class);
        verify(breakGlassService).openApiBreakGlassReview(captor.capture());
        assertThat(captor.getValue().organizationId()).isEqualTo(orgId);
        assertThat(captor.getValue().apiRequestId()).isEqualTo(apiRequestId);
        assertThat(captor.getValue().connectorId()).isEqualTo(connectorId);
        assertThat(captor.getValue().submitterUserId()).isEqualTo(submitterId);
        assertThat(captor.getValue().justification()).isEqualTo("prod is down");
    }
}
