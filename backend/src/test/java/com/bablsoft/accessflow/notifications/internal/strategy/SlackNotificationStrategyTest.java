package com.bablsoft.accessflow.notifications.internal.strategy;

import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.RiskLevel;
import com.bablsoft.accessflow.notifications.api.NotificationChannelType;
import com.bablsoft.accessflow.notifications.api.NotificationDeliveryException;
import com.bablsoft.accessflow.notifications.api.NotificationEventType;
import com.bablsoft.accessflow.notifications.internal.DecryptedSlackApp;
import com.bablsoft.accessflow.notifications.internal.DefaultSlackAppConfigService;
import com.bablsoft.accessflow.notifications.internal.NotificationContext;
import com.bablsoft.accessflow.notifications.internal.codec.ChannelConfigCodec;
import com.bablsoft.accessflow.notifications.internal.codec.SlackChannelConfig;
import com.bablsoft.accessflow.notifications.internal.persistence.entity.NotificationChannelEntity;
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
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SlackNotificationStrategyTest {

    private ChannelConfigCodec codec;
    private SlackBlockKitFactory factory;
    private SlackBotMessenger botMessenger;
    private DefaultSlackAppConfigService appConfigService;
    private Slack slack;
    private SlackNotificationStrategy strategy;

    @BeforeEach
    void setUp() {
        codec = mock(ChannelConfigCodec.class);
        factory = mock(SlackBlockKitFactory.class);
        botMessenger = mock(SlackBotMessenger.class);
        appConfigService = mock(DefaultSlackAppConfigService.class);
        slack = mock(Slack.class);
        strategy = new SlackNotificationStrategy(codec, factory, botMessenger, appConfigService, slack);

        when(codec.decodeSlack(anyString())).thenReturn(new SlackChannelConfig(
                URI.create("https://hooks.slack.com/services/abc"),
                "#review",
                List.of("@alice")));
        when(factory.buildEventPayload(any(), anyString())).thenReturn(Payload.builder().build());
        when(factory.buildTestPayload(anyString())).thenReturn(Payload.builder().build());
        when(appConfigService.findActiveByOrg(any())).thenReturn(Optional.empty());
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
        verify(slack).send(urlCaptor.capture(), any(Payload.class));
        assertThat(urlCaptor.getValue()).isEqualTo("https://hooks.slack.com/services/abc");
        verify(factory).buildEventPayload(any(), eq("#review"));
        verify(botMessenger, never()).postMessage(anyString(), anyString(), anyString(), any());
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
    void deliverUsesBotTokenWithActionButtonsWhenAppConfigured() throws Exception {
        var orgId = UUID.randomUUID();
        when(appConfigService.findActiveByOrg(orgId))
                .thenReturn(Optional.of(new DecryptedSlackApp(orgId, "A1", "xoxb-token", "sign", "C-default")));
        when(factory.fallbackText(any())).thenReturn("🔍 New Query Awaiting Review");
        when(factory.buildBlocks(any(), eq(true))).thenReturn(List.of());

        strategy.deliver(ctx(NotificationEventType.QUERY_SUBMITTED, orgId), channel(orgId));

        verify(botMessenger).postMessage(eq("xoxb-token"), eq("#review"),
                eq("🔍 New Query Awaiting Review"), any());
        verify(factory).buildBlocks(any(), eq(true));
        verify(slack, never()).send(anyString(), any(Payload.class));
    }

    @Test
    void deliverViaBotOmitsActionButtonsForNonSubmittedEvents() throws Exception {
        var orgId = UUID.randomUUID();
        when(appConfigService.findActiveByOrg(orgId))
                .thenReturn(Optional.of(new DecryptedSlackApp(orgId, "A1", "xoxb-token", "sign", "C-default")));
        when(factory.buildBlocks(any(), eq(false))).thenReturn(List.of());

        strategy.deliver(ctx(NotificationEventType.QUERY_APPROVED, orgId), channel(orgId));

        verify(factory).buildBlocks(any(), eq(false));
        verify(botMessenger).postMessage(eq("xoxb-token"), eq("#review"), any(), any());
    }

    @Test
    void deliverViaBotFallsBackToDefaultChannelWhenChannelOverrideBlank() throws Exception {
        var orgId = UUID.randomUUID();
        when(codec.decodeSlack(anyString())).thenReturn(new SlackChannelConfig(
                URI.create("https://hooks.slack.com/services/abc"), null, List.of()));
        when(appConfigService.findActiveByOrg(orgId))
                .thenReturn(Optional.of(new DecryptedSlackApp(orgId, "A1", "xoxb-token", "sign", "C-default")));
        when(factory.buildBlocks(any(), eq(true))).thenReturn(List.of());

        strategy.deliver(ctx(NotificationEventType.QUERY_SUBMITTED, orgId), channel(orgId));

        verify(botMessenger).postMessage(eq("xoxb-token"), eq("C-default"), any(), any());
    }

    @Test
    void sendTestPostsTestPayload() throws Exception {
        when(slack.send(anyString(), any(Payload.class))).thenReturn(success(200));

        strategy.sendTest(channel(), null);

        verify(factory).buildTestPayload(eq("#review"));
        verify(slack).send(eq("https://hooks.slack.com/services/abc"), any(Payload.class));
    }

    @Test
    void sendTestUsesBotTokenWhenAppConfigured() throws Exception {
        var orgId = UUID.randomUUID();
        when(appConfigService.findActiveByOrg(orgId))
                .thenReturn(Optional.of(new DecryptedSlackApp(orgId, "A1", "xoxb-token", "sign", "C-default")));
        when(factory.testText()).thenReturn("test ok");

        strategy.sendTest(channel(orgId), null);

        verify(botMessenger).postMessage(eq("xoxb-token"), eq("#review"), eq("test ok"), isNull());
        verify(slack, never()).send(anyString(), any(Payload.class));
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
        return channel(UUID.randomUUID());
    }

    private static NotificationChannelEntity channel(UUID organizationId) {
        var c = new NotificationChannelEntity();
        c.setId(UUID.randomUUID());
        c.setOrganizationId(organizationId);
        c.setChannelType(NotificationChannelType.SLACK);
        c.setName("Slack");
        c.setActive(true);
        c.setConfigJson("{}");
        c.setCreatedAt(Instant.now());
        return c;
    }

    private static NotificationContext ctx(NotificationEventType eventType) {
        return ctx(eventType, UUID.randomUUID());
    }

    private static NotificationContext ctx(NotificationEventType eventType, UUID organizationId) {
        return new NotificationContext(
                eventType,
                organizationId,
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
                Instant.now(),
                "en",
                null);
    }
}
