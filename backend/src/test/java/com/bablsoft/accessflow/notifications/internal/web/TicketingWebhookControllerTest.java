package com.bablsoft.accessflow.notifications.internal.web;

import com.bablsoft.accessflow.notifications.api.NotificationChannelType;
import com.bablsoft.accessflow.notifications.internal.TicketingInboundService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TicketingWebhookControllerTest {

    @Mock TicketingInboundService inboundService;

    private TicketingWebhookController controller;

    private final UUID channelId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        controller = new TicketingWebhookController(inboundService);
    }

    @Test
    void servicenowDelegatesWithServiceNowSystemAndMapsResult() {
        when(inboundService.handle(NotificationChannelType.SERVICENOW, channelId,
                "{\"a\":1}", "123", "sha256=x"))
                .thenReturn(new TicketingInboundService.InboundResult(200, "decision_applied"));

        var response = controller.servicenow(channelId, "{\"a\":1}", "123", "sha256=x");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo(Map.of("result", "decision_applied"));
        verify(inboundService).handle(NotificationChannelType.SERVICENOW, channelId,
                "{\"a\":1}", "123", "sha256=x");
    }

    @Test
    void jiraDelegatesWithJiraSystemAndMapsResult() {
        when(inboundService.handle(NotificationChannelType.JIRA, channelId,
                "{}", "456", "sha256=y"))
                .thenReturn(new TicketingInboundService.InboundResult(401, "invalid_signature"));

        var response = controller.jira(channelId, "{}", "456", "sha256=y");

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        assertThat(response.getBody()).isEqualTo(Map.of("result", "invalid_signature"));
    }

    @Test
    void nonOkStatusFromServicePropagatesToResponse() {
        when(inboundService.handle(NotificationChannelType.SERVICENOW, channelId,
                null, null, null))
                .thenReturn(new TicketingInboundService.InboundResult(404, "unknown_channel"));

        var response = controller.servicenow(channelId, null, null, null);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody()).isEqualTo(Map.of("result", "unknown_channel"));
    }
}
