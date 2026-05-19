package com.bablsoft.accessflow.notifications.internal.strategy;

import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.RiskLevel;
import com.bablsoft.accessflow.notifications.api.NotificationChannelType;
import com.bablsoft.accessflow.notifications.api.NotificationDeliveryException;
import com.bablsoft.accessflow.notifications.api.NotificationEventType;
import com.bablsoft.accessflow.notifications.internal.NotificationContext;
import com.bablsoft.accessflow.notifications.internal.codec.ChannelConfigCodec;
import com.bablsoft.accessflow.notifications.internal.codec.MsTeamsChannelConfig;
import com.bablsoft.accessflow.notifications.internal.persistence.entity.NotificationChannelEntity;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MsTeamsNotificationStrategyTest {

    private HttpServer server;
    private final ConcurrentLinkedQueue<CapturedRequest> requests = new ConcurrentLinkedQueue<>();
    private AtomicInteger nextResponseCode;
    private MsTeamsNotificationStrategy strategy;
    private NotificationChannelEntity channel;

    @BeforeEach
    void setUp() throws IOException {
        nextResponseCode = new AtomicInteger(200);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        var port = server.getAddress().getPort();

        var codec = mock(ChannelConfigCodec.class);
        when(codec.decodeMsTeams(anyString())).thenReturn(new MsTeamsChannelConfig(
                URI.create("http://127.0.0.1:" + port + "/webhookb2/abc")));

        var payloadFactory = new MsTeamsPayloadFactory(tools.jackson.databind.json.JsonMapper.builder().build());
        strategy = new MsTeamsNotificationStrategy(codec, payloadFactory, RestClient.create());

        channel = new NotificationChannelEntity();
        channel.setId(UUID.randomUUID());
        channel.setOrganizationId(UUID.randomUUID());
        channel.setChannelType(NotificationChannelType.MS_TEAMS);
        channel.setName("Teams");
        channel.setActive(true);
        channel.setConfigJson("{}");
        channel.setCreatedAt(Instant.now());
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void supportsMsTeams() {
        assertThat(strategy.supports()).isEqualTo(NotificationChannelType.MS_TEAMS);
    }

    @Test
    void deliverPostsAdaptiveCardEnvelope() {
        strategy.deliver(ctx(NotificationEventType.QUERY_SUBMITTED), channel);
        assertThat(requests).hasSize(1);
        var req = requests.peek();
        assertThat(req.contentType).startsWith("application/json");
        assertThat(req.body).contains("\"type\":\"message\"")
                .contains("AdaptiveCard")
                .contains("New Query Awaiting Review")
                .contains("Production");
    }

    @Test
    void deliverThrowsOnNon2xx() {
        nextResponseCode.set(503);
        assertThatThrownBy(() -> strategy.deliver(ctx(NotificationEventType.QUERY_APPROVED), channel))
                .isInstanceOf(NotificationDeliveryException.class)
                .hasMessageContaining("503");
    }

    @Test
    void sendTestPostsTestBody() {
        strategy.sendTest(channel, null);
        assertThat(requests).hasSize(1);
        assertThat(requests.peek().body).contains("test successful");
    }

    @Test
    void sendTestPropagatesFailure() {
        nextResponseCode.set(500);
        assertThatThrownBy(() -> strategy.sendTest(channel, null))
                .isInstanceOf(NotificationDeliveryException.class);
    }

    private void handle(HttpExchange exchange) throws IOException {
        var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        requests.add(new CapturedRequest(
                exchange.getRequestHeaders().getFirst("Content-Type"), body));
        exchange.sendResponseHeaders(nextResponseCode.get(), -1);
        exchange.close();
    }

    private record CapturedRequest(String contentType, String body) {
    }

    private static NotificationContext ctx(NotificationEventType eventType) {
        return new NotificationContext(
                eventType,
                UUID.randomUUID(),
                UUID.randomUUID(),
                QueryType.UPDATE,
                "UPDATE orders SET status='shipped'",
                "UPDATE orders SET status='shipped'",
                "UPDATE orders SET status='shipped'",
                RiskLevel.MEDIUM,
                42,
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
