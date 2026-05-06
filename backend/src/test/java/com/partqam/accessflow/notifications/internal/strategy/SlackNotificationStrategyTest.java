package com.partqam.accessflow.notifications.internal.strategy;

import com.partqam.accessflow.core.api.QueryType;
import com.partqam.accessflow.core.api.RiskLevel;
import com.partqam.accessflow.notifications.api.NotificationChannelType;
import com.partqam.accessflow.notifications.api.NotificationDeliveryException;
import com.partqam.accessflow.notifications.api.NotificationEventType;
import com.partqam.accessflow.notifications.internal.NotificationContext;
import com.partqam.accessflow.notifications.internal.codec.ChannelConfigCodec;
import com.partqam.accessflow.notifications.internal.codec.SlackChannelConfig;
import com.partqam.accessflow.notifications.internal.persistence.entity.NotificationChannelEntity;
import com.slack.api.Slack;
import com.slack.api.webhook.Payload;
import com.slack.api.webhook.WebhookResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SlackNotificationStrategyTest {

    private ChannelConfigCodec codec;
    private SlackBlockKitFactory factory;
    private Slack slack;
    private SlackNotificationStrategy strategy;

    @BeforeEach
    void setUp() {
        codec = mock(ChannelConfigCodec.class);
        factory = mock(SlackBlockKitFactory.class);
        slack = mock(Slack.class);
        strategy = new SlackNotificationStrategy(codec, factory, slack);

        when(codec.decodeSlack(anyString())).thenReturn(new SlackChannelConfig(
                URI.create("https://hooks.slack.com/services/abc"),
                "#review",
                List.of("@alice")));
        when(factory.buildEventPayload(any(), anyString())).thenReturn(Payload.builder().build());
        when(factory.buildTestPayload(anyString())).thenReturn(Payload.builder().build());
    }

    @Test
    void supportsSlack() {
        assertThat(strategy.supports()).isEqualTo(NotificationChannelType.SLACK);
    }

    @Test
    void deliverPostsBuiltPayloadOnSuccess() throws Exception {
        when(slack.send(anyString(), any(Payload.class))).thenReturn(success(200));

        strategy.deliver(ctx(NotificationEventType.QUERY_SUBMITTED), channel());

        var urlCaptor = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(slack).send(urlCaptor.capture(), any(Payload.class));
        assertThat(urlCaptor.getValue()).isEqualTo("https://hooks.slack.com/services/abc");
        org.mockito.Mockito.verify(factory).buildEventPayload(any(), eq("#review"));
    }

    @Test
    void deliverThrowsOnNon2xx() throws Exception {
        when(slack.send(anyString(), any(Payload.class))).thenReturn(success(403));

        assertThatThrownBy(() -> strategy.deliver(ctx(NotificationEventType.QUERY_APPROVED), channel()))
                .isInstanceOf(NotificationDeliveryException.class)
                .hasMessageContaining("403");
    }

    @Test
    void deliverThrowsWhenResponseCodeIsNull() throws Exception {
        when(slack.send(anyString(), any(Payload.class)))
                .thenReturn(WebhookResponse.builder().code(null).build());

        assertThatThrownBy(() -> strategy.deliver(ctx(NotificationEventType.QUERY_REJECTED), channel()))
                .isInstanceOf(NotificationDeliveryException.class);
    }

    @Test
    void deliverWrapsIoExceptionAsDeliveryException() throws Exception {
        when(slack.send(anyString(), any(Payload.class))).thenThrow(new IOException("network"));

        assertThatThrownBy(() -> strategy.deliver(ctx(NotificationEventType.AI_HIGH_RISK), channel()))
                .isInstanceOf(NotificationDeliveryException.class)
                .hasMessageContaining("Slack delivery failed");
    }

    @Test
    void deliverIncludesResponseBodyInErrorMessage() throws Exception {
        when(slack.send(anyString(), any(Payload.class)))
                .thenReturn(WebhookResponse.builder()
                        .code(500).message("Server Error").body("upstream busted").build());

        assertThatThrownBy(() -> strategy.deliver(ctx(NotificationEventType.QUERY_SUBMITTED), channel()))
                .isInstanceOf(NotificationDeliveryException.class)
                .hasMessageContaining("upstream busted");
    }

    @Test
    void sendTestPostsTestPayload() throws Exception {
        when(slack.send(anyString(), any(Payload.class))).thenReturn(success(200));

        strategy.sendTest(channel(), null);

        org.mockito.Mockito.verify(factory).buildTestPayload(eq("#review"));
        org.mockito.Mockito.verify(slack).send(eq("https://hooks.slack.com/services/abc"),
                any(Payload.class));
    }

    @Test
    void sendTestPropagatesFailure() throws Exception {
        when(slack.send(anyString(), any(Payload.class))).thenReturn(success(404));

        assertThatThrownBy(() -> strategy.sendTest(channel(), null))
                .isInstanceOf(NotificationDeliveryException.class);
    }

    private static WebhookResponse success(int code) {
        return WebhookResponse.builder().code(code).message("OK").body("ok").build();
    }

    private static NotificationChannelEntity channel() {
        var c = new NotificationChannelEntity();
        c.setId(UUID.randomUUID());
        c.setOrganizationId(UUID.randomUUID());
        c.setChannelType(NotificationChannelType.SLACK);
        c.setName("Slack");
        c.setActive(true);
        c.setConfigJson("{}");
        c.setCreatedAt(Instant.now());
        return c;
    }

    private static NotificationContext ctx(NotificationEventType eventType) {
        return new NotificationContext(
                eventType,
                UUID.randomUUID(),
                UUID.randomUUID(),
                QueryType.SELECT,
                "SELECT 1",
                "SELECT 1",
                "SELECT 1",
                RiskLevel.LOW,
                10,
                "ok",
                UUID.randomUUID(),
                "Production",
                UUID.randomUUID(),
                "alice@example.com",
                "Alice",
                null,
                null,
                null,
                null,
                URI.create("https://app.example.com/queries/abc"),
                List.of(),
                Instant.now());
    }
}
