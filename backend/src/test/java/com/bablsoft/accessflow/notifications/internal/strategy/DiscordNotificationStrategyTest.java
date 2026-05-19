package com.bablsoft.accessflow.notifications.internal.strategy;

import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.RiskLevel;
import com.bablsoft.accessflow.notifications.api.NotificationChannelType;
import com.bablsoft.accessflow.notifications.api.NotificationDeliveryException;
import com.bablsoft.accessflow.notifications.api.NotificationEventType;
import com.bablsoft.accessflow.notifications.internal.NotificationContext;
import com.bablsoft.accessflow.notifications.internal.codec.ChannelConfigCodec;
import com.bablsoft.accessflow.notifications.internal.codec.DiscordChannelConfig;
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

class DiscordNotificationStrategyTest {

    private HttpServer server;
    private final ConcurrentLinkedQueue<CapturedRequest> requests = new ConcurrentLinkedQueue<>();
    private AtomicInteger nextResponseCode;
    private ChannelConfigCodec codec;
    private DiscordNotificationStrategy strategy;
    private NotificationChannelEntity channel;

    @BeforeEach
    void setUp() throws IOException {
        nextResponseCode = new AtomicInteger(204);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        var port = server.getAddress().getPort();

        codec = mock(ChannelConfigCodec.class);
        when(codec.decodeDiscord(anyString())).thenReturn(new DiscordChannelConfig(
                URI.create("http://127.0.0.1:" + port + "/webhook"),
                "AccessFlow",
                null));

        var payloadFactory = new DiscordPayloadFactory(tools.jackson.databind.json.JsonMapper.builder().build());
        strategy = new DiscordNotificationStrategy(codec, payloadFactory, RestClient.create());

        channel = new NotificationChannelEntity();
        channel.setId(UUID.randomUUID());
        channel.setOrganizationId(UUID.randomUUID());
        channel.setChannelType(NotificationChannelType.DISCORD);
        channel.setName("Discord");
        channel.setActive(true);
        channel.setConfigJson("{}");
        channel.setCreatedAt(Instant.now());
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void supportsDiscord() {
        assertThat(strategy.supports()).isEqualTo(NotificationChannelType.DISCORD);
    }

    @Test
    void deliverPostsJsonBody() {
        strategy.deliver(ctx(NotificationEventType.QUERY_SUBMITTED), channel);
        assertThat(requests).hasSize(1);
        var req = requests.peek();
        assertThat(req.contentType).startsWith("application/json");
        assertThat(req.body).contains("\"username\":\"AccessFlow\"")
                .contains("New Query Awaiting Review")
                .contains("Production")
                .contains("alice@example.com");
    }

    @Test
    void deliverThrowsOnNon2xx() {
        nextResponseCode.set(500);
        assertThatThrownBy(() -> strategy.deliver(ctx(NotificationEventType.QUERY_APPROVED), channel))
                .isInstanceOf(NotificationDeliveryException.class)
                .hasMessageContaining("500");
    }

    @Test
    void sendTestPostsTestBody() {
        strategy.sendTest(channel, null);
        assertThat(requests).hasSize(1);
        assertThat(requests.peek().body).contains("test successful");
    }

    @Test
    void sendTestPropagatesFailure() {
        nextResponseCode.set(403);
        assertThatThrownBy(() -> strategy.sendTest(channel, null))
                .isInstanceOf(NotificationDeliveryException.class);
    }

    private void handle(HttpExchange exchange) throws IOException {
        var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        requests.add(new CapturedRequest(
                exchange.getRequestHeaders().getFirst("Content-Type"),
                body));
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
