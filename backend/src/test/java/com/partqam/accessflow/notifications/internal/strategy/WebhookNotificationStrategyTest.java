package com.partqam.accessflow.notifications.internal.strategy;

import com.partqam.accessflow.core.api.QueryType;
import com.partqam.accessflow.core.api.RiskLevel;
import com.partqam.accessflow.notifications.api.NotificationChannelType;
import com.partqam.accessflow.notifications.api.NotificationEventType;
import com.partqam.accessflow.notifications.internal.NotificationContext;
import com.partqam.accessflow.notifications.internal.codec.ChannelConfigCodec;
import com.partqam.accessflow.notifications.internal.codec.WebhookChannelConfig;
import com.partqam.accessflow.notifications.internal.config.NotificationsProperties;
import com.partqam.accessflow.notifications.internal.persistence.entity.NotificationChannelEntity;
import com.partqam.accessflow.notifications.internal.persistence.repo.NotificationChannelRepository;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WebhookNotificationStrategyTest {

    private HttpServer server;
    private final ConcurrentLinkedQueue<CapturedRequest> requests = new ConcurrentLinkedQueue<>();
    private AtomicInteger nextResponseCode;
    private ChannelConfigCodec codec;
    private NotificationChannelRepository channelRepository;
    private RecordingTaskScheduler taskScheduler;
    private NotificationsProperties properties;
    private WebhookPayloadFactory payloadFactory;
    private WebhookNotificationStrategy strategy;
    private NotificationChannelEntity channel;

    @BeforeEach
    void setUp() throws IOException {
        nextResponseCode = new AtomicInteger(204);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();

        codec = mock(ChannelConfigCodec.class);
        channelRepository = mock(NotificationChannelRepository.class);
        taskScheduler = new RecordingTaskScheduler();
        payloadFactory = mock(WebhookPayloadFactory.class);
        properties = new NotificationsProperties(
                URI.create("https://app.example.test"),
                new NotificationsProperties.Retry(
                        Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(3)));
        strategy = new WebhookNotificationStrategy(
                codec, payloadFactory, RestClient.create(),
                taskScheduler, properties, channelRepository);

        channel = new NotificationChannelEntity();
        channel.setId(UUID.randomUUID());
        channel.setOrganizationId(UUID.randomUUID());
        channel.setChannelType(NotificationChannelType.WEBHOOK);
        channel.setName("Webhook");
        channel.setActive(true);
        channel.setConfigJson("{}");
        channel.setCreatedAt(Instant.now());

        when(channelRepository.findById(channel.getId())).thenReturn(Optional.of(channel));
        var port = server.getAddress().getPort();
        when(codec.decodeWebhook(anyString())).thenReturn(new WebhookChannelConfig(
                URI.create("http://127.0.0.1:" + port + "/hook"), "topsecret", 5));
        when(payloadFactory.buildBody(any())).thenReturn("{\"event\":\"X\"}");
        when(payloadFactory.buildTestBody()).thenReturn("{\"event\":\"TEST\"}");
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void supportsWebhook() {
        assertThat(strategy.supports()).isEqualTo(NotificationChannelType.WEBHOOK);
    }

    @Test
    void successfulDeliveryDoesNotSchedule() {
        nextResponseCode.set(204);
        strategy.deliver(ctx(), channel);
        assertThat(requests).hasSize(1);
        var req = requests.peek();
        assertThat(req.event).isEqualTo("QUERY_SUBMITTED");
        assertThat(req.signature).startsWith("sha256=");
        assertThat(req.delivery).isNotBlank();
        assertThat(req.body).isEqualTo("{\"event\":\"X\"}");
        assertThat(taskScheduler.scheduled).isEmpty();
    }

    @Test
    void failedDeliverySchedulesRetryWithFirstDelay() {
        nextResponseCode.set(500);
        strategy.deliver(ctx(), channel);

        assertThat(taskScheduler.scheduled).hasSize(1);
        var elapsed = Duration.between(Instant.now(), taskScheduler.scheduled.get(0).when);
        // The runtime computes Instant.now().plus(retry.first()) ≈ now+1s, so the
        // expected delay is just under 1 second by the time we assert here.
        assertThat(elapsed).isLessThanOrEqualTo(Duration.ofSeconds(1)).isPositive();
    }

    @Test
    void retryAttemptsCascadeUntilExhausted() {
        nextResponseCode.set(500);
        strategy.deliver(ctx(), channel);
        // Initial attempt failed; one retry queued. Run it manually:
        runAllScheduled();
        runAllScheduled();
        runAllScheduled();
        // After 3 retries (attempts 1,2,3) all failing, the next attempt has no schedule.
        runAllScheduled();
        assertThat(taskScheduler.scheduled).isEmpty();
        // Total HTTP calls = 4 (1 initial + 3 retries).
        assertThat(requests).hasSize(4);
    }

    @Test
    void retryStopsWhenChannelMissing() {
        nextResponseCode.set(500);
        strategy.deliver(ctx(), channel);
        // Now simulate channel deletion before the retry runs.
        when(channelRepository.findById(channel.getId())).thenReturn(Optional.empty());
        runAllScheduled();
        // No additional requests should be made.
        assertThat(requests).hasSize(1);
        // No further retries scheduled.
        assertThat(taskScheduler.scheduled).isEmpty();
    }

    @Test
    void retryStopsWhenChannelDeactivated() {
        nextResponseCode.set(500);
        strategy.deliver(ctx(), channel);
        channel.setActive(false);
        runAllScheduled();
        assertThat(requests).hasSize(1);
        assertThat(taskScheduler.scheduled).isEmpty();
    }

    @Test
    void sendTestPostsTestBodyWithoutScheduling() {
        nextResponseCode.set(200);
        strategy.sendTest(channel, null);
        assertThat(requests).hasSize(1);
        assertThat(requests.peek().body).isEqualTo("{\"event\":\"TEST\"}");
        assertThat(requests.peek().event).isEqualTo("TEST");
        assertThat(taskScheduler.scheduled).isEmpty();
    }

    @Test
    void sendTestPropagatesDeliveryFailure() {
        nextResponseCode.set(500);
        assertThatThrownBy(() -> strategy.sendTest(channel, null))
                .isInstanceOf(com.partqam.accessflow.notifications.api.NotificationDeliveryException.class);
    }

    private void runAllScheduled() {
        var pending = new java.util.ArrayList<>(taskScheduler.scheduled);
        taskScheduler.scheduled.clear();
        for (var s : pending) {
            s.task.run();
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        requests.add(new CapturedRequest(
                exchange.getRequestHeaders().getFirst("X-AccessFlow-Event"),
                exchange.getRequestHeaders().getFirst("X-AccessFlow-Signature"),
                exchange.getRequestHeaders().getFirst("X-AccessFlow-Delivery"),
                body));
        exchange.sendResponseHeaders(nextResponseCode.get(), -1);
        exchange.close();
    }

    private NotificationContext ctx() {
        return new NotificationContext(
                NotificationEventType.QUERY_SUBMITTED,
                UUID.randomUUID(), UUID.randomUUID(), QueryType.UPDATE,
                "UPDATE x", "UPDATE x", "UPDATE x",
                RiskLevel.LOW, 10, "ok",
                UUID.randomUUID(), "Production",
                UUID.randomUUID(), "alice@example.com", "Alice",
                null, null, null, null,
                URI.create("https://app.example.test/queries/abc"),
                List.of(), Instant.now());
    }

    private record CapturedRequest(String event, String signature, String delivery, String body) {
    }

    private static final class RecordingTaskScheduler implements TaskScheduler {
        record Scheduled(Runnable task, Instant when) {
        }

        final java.util.List<Scheduled> scheduled = new java.util.concurrent.CopyOnWriteArrayList<>();

        @Override
        public java.util.concurrent.ScheduledFuture<?> schedule(Runnable task, Instant startTime) {
            scheduled.add(new Scheduled(task, startTime));
            return null;
        }

        @Override
        public java.util.concurrent.ScheduledFuture<?> schedule(Runnable task,
                org.springframework.scheduling.Trigger trigger) {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.util.concurrent.ScheduledFuture<?> scheduleAtFixedRate(Runnable task,
                Instant startTime, Duration period) {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.util.concurrent.ScheduledFuture<?> scheduleAtFixedRate(Runnable task,
                Duration period) {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.util.concurrent.ScheduledFuture<?> scheduleWithFixedDelay(Runnable task,
                Instant startTime, Duration delay) {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.util.concurrent.ScheduledFuture<?> scheduleWithFixedDelay(Runnable task,
                Duration delay) {
            throw new UnsupportedOperationException();
        }
    }
}
