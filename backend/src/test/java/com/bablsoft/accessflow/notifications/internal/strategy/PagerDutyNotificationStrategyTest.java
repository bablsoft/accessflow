package com.bablsoft.accessflow.notifications.internal.strategy;

import com.bablsoft.accessflow.audit.events.NotificationDeliveryExhaustedEvent;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.RiskLevel;
import com.bablsoft.accessflow.notifications.api.NotificationChannelType;
import com.bablsoft.accessflow.notifications.api.NotificationDeliveryException;
import com.bablsoft.accessflow.notifications.api.NotificationEventType;
import com.bablsoft.accessflow.notifications.internal.NotificationContext;
import com.bablsoft.accessflow.notifications.internal.codec.ChannelConfigCodec;
import com.bablsoft.accessflow.notifications.internal.codec.PagerDutyChannelConfig;
import com.bablsoft.accessflow.notifications.internal.codec.PagerDutySeverity;
import com.bablsoft.accessflow.notifications.internal.codec.PagerDutyTrigger;
import com.bablsoft.accessflow.notifications.internal.config.NotificationsProperties;
import com.bablsoft.accessflow.notifications.internal.persistence.entity.NotificationChannelEntity;
import com.bablsoft.accessflow.notifications.internal.persistence.repo.NotificationChannelRepository;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PagerDutyNotificationStrategyTest {

    private HttpServer server;
    private final ConcurrentLinkedQueue<CapturedRequest> requests = new ConcurrentLinkedQueue<>();
    private AtomicInteger nextResponseCode;
    private ChannelConfigCodec codec;
    private NotificationChannelRepository channelRepository;
    private RecordingTaskScheduler taskScheduler;
    private NotificationsProperties properties;
    private PagerDutyPayloadFactory payloadFactory;
    private ApplicationEventPublisher eventPublisher;
    private PagerDutyNotificationStrategy strategy;
    private NotificationChannelEntity channel;

    @BeforeEach
    void setUp() throws IOException {
        nextResponseCode = new AtomicInteger(202);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();

        codec = mock(ChannelConfigCodec.class);
        channelRepository = mock(NotificationChannelRepository.class);
        taskScheduler = new RecordingTaskScheduler();
        payloadFactory = mock(PagerDutyPayloadFactory.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        var port = server.getAddress().getPort();
        properties = new NotificationsProperties(
                URI.create("https://app.example.test"),
                new NotificationsProperties.Retry(
                        Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(3)),
                null,
                URI.create("http://127.0.0.1:" + port + "/"));
        strategy = new PagerDutyNotificationStrategy(
                codec, payloadFactory, RestClient.create(),
                taskScheduler, properties, channelRepository, eventPublisher);

        channel = new NotificationChannelEntity();
        channel.setId(UUID.randomUUID());
        channel.setOrganizationId(UUID.randomUUID());
        channel.setChannelType(NotificationChannelType.PAGERDUTY);
        channel.setName("PagerDuty");
        channel.setActive(true);
        channel.setConfigJson("{}");
        channel.setCreatedAt(Instant.now());

        when(channelRepository.findById(channel.getId())).thenReturn(Optional.of(channel));
        when(payloadFactory.buildEventBody(any(), any())).thenReturn("{\"event_action\":\"trigger\"}");
        when(payloadFactory.buildTestBody(any())).thenReturn("{\"dedup_key\":\"accessflow-test\"}");
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private void stubConfig(Set<PagerDutyTrigger> triggers) {
        when(codec.decodePagerDuty(anyString())).thenReturn(
                new PagerDutyChannelConfig("R0UT1NGKEY", PagerDutySeverity.CRITICAL, triggers));
    }

    @Test
    void supportsPagerDuty() {
        assertThat(strategy.supports()).isEqualTo(NotificationChannelType.PAGERDUTY);
    }

    @Test
    void matchingCriticalRiskEventDelivers() {
        stubConfig(EnumSet.of(PagerDutyTrigger.CRITICAL_RISK));
        strategy.deliver(ctx(NotificationEventType.AI_HIGH_RISK), channel);

        assertThat(requests).hasSize(1);
        assertThat(requests.peek().path).isEqualTo("/v2/enqueue");
        assertThat(taskScheduler.scheduled).isEmpty();
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void matchingReviewTimeoutEventDelivers() {
        stubConfig(EnumSet.of(PagerDutyTrigger.REVIEW_TIMEOUT));
        strategy.deliver(ctx(NotificationEventType.REVIEW_TIMEOUT), channel);

        assertThat(requests).hasSize(1);
    }

    @Test
    void nonMatchingTriggerDoesNotFire() {
        stubConfig(EnumSet.of(PagerDutyTrigger.REVIEW_TIMEOUT));
        strategy.deliver(ctx(NotificationEventType.AI_HIGH_RISK), channel);

        assertThat(requests).isEmpty();
        assertThat(taskScheduler.scheduled).isEmpty();
    }

    @Test
    void unmappedEventDoesNotFire() {
        stubConfig(EnumSet.allOf(PagerDutyTrigger.class));
        strategy.deliver(ctx(NotificationEventType.QUERY_SUBMITTED), channel);

        assertThat(requests).isEmpty();
        assertThat(taskScheduler.scheduled).isEmpty();
    }

    @Test
    void transient5xxSchedulesRetry() {
        stubConfig(EnumSet.of(PagerDutyTrigger.CRITICAL_RISK));
        nextResponseCode.set(500);
        strategy.deliver(ctx(NotificationEventType.AI_HIGH_RISK), channel);

        assertThat(taskScheduler.scheduled).hasSize(1);
        var elapsed = Duration.between(Instant.now(), taskScheduler.scheduled.get(0).when);
        assertThat(elapsed).isLessThanOrEqualTo(Duration.ofSeconds(1)).isPositive();
    }

    @Test
    void retryAttemptsCascadeUntilExhausted() {
        stubConfig(EnumSet.of(PagerDutyTrigger.CRITICAL_RISK));
        nextResponseCode.set(500);
        strategy.deliver(ctx(NotificationEventType.AI_HIGH_RISK), channel);
        runAllScheduled();
        runAllScheduled();
        runAllScheduled();
        runAllScheduled();
        assertThat(taskScheduler.scheduled).isEmpty();
        assertThat(requests).hasSize(4);
    }

    @Test
    void exhaustedRetriesPublishSingleExhaustedEvent() {
        stubConfig(EnumSet.of(PagerDutyTrigger.CRITICAL_RISK));
        nextResponseCode.set(503);
        strategy.deliver(ctx(NotificationEventType.AI_HIGH_RISK), channel);
        verifyNoInteractions(eventPublisher);
        runAllScheduled();
        runAllScheduled();
        verify(eventPublisher, never()).publishEvent(any(NotificationDeliveryExhaustedEvent.class));
        runAllScheduled();
        runAllScheduled();

        var captor = ArgumentCaptor.forClass(NotificationDeliveryExhaustedEvent.class);
        verify(eventPublisher, times(1)).publishEvent(captor.capture());

        var event = captor.getValue();
        assertThat(event.channelId()).isEqualTo(channel.getId());
        assertThat(event.organizationId()).isEqualTo(channel.getOrganizationId());
        assertThat(event.channelType()).isEqualTo(NotificationChannelType.PAGERDUTY.name());
        assertThat(event.eventType()).isEqualTo(NotificationEventType.AI_HIGH_RISK.name());
        assertThat(event.attemptCount()).isEqualTo(4);
        assertThat(event.lastHttpStatus()).isEqualTo(503);
        assertThat(event.lastError()).contains("503");
    }

    @Test
    void successfulDeliveryDoesNotPublishExhaustedEvent() {
        stubConfig(EnumSet.of(PagerDutyTrigger.CRITICAL_RISK));
        nextResponseCode.set(202);
        strategy.deliver(ctx(NotificationEventType.AI_HIGH_RISK), channel);
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void retryStopsWhenChannelMissing() {
        stubConfig(EnumSet.of(PagerDutyTrigger.CRITICAL_RISK));
        nextResponseCode.set(500);
        strategy.deliver(ctx(NotificationEventType.AI_HIGH_RISK), channel);
        when(channelRepository.findById(channel.getId())).thenReturn(Optional.empty());
        runAllScheduled();
        assertThat(requests).hasSize(1);
        assertThat(taskScheduler.scheduled).isEmpty();
    }

    @Test
    void retryStopsWhenChannelDeactivated() {
        stubConfig(EnumSet.of(PagerDutyTrigger.CRITICAL_RISK));
        nextResponseCode.set(500);
        strategy.deliver(ctx(NotificationEventType.AI_HIGH_RISK), channel);
        channel.setActive(false);
        runAllScheduled();
        assertThat(requests).hasSize(1);
        assertThat(taskScheduler.scheduled).isEmpty();
    }

    @Test
    void sendTestPostsTestBodyWithoutSchedulingAndBypassesFilter() {
        stubConfig(EnumSet.noneOf(PagerDutyTrigger.class));
        nextResponseCode.set(202);
        strategy.sendTest(channel, null);
        assertThat(requests).hasSize(1);
        assertThat(requests.peek().body).isEqualTo("{\"dedup_key\":\"accessflow-test\"}");
        assertThat(taskScheduler.scheduled).isEmpty();
    }

    @Test
    void sendTestPropagatesDeliveryFailure() {
        stubConfig(EnumSet.of(PagerDutyTrigger.CRITICAL_RISK));
        nextResponseCode.set(500);
        assertThatThrownBy(() -> strategy.sendTest(channel, null))
                .isInstanceOf(NotificationDeliveryException.class);
    }

    private void runAllScheduled() {
        var pending = new java.util.ArrayList<>(taskScheduler.scheduled);
        taskScheduler.scheduled.clear();
        for (var s : pending) {
            s.task.run();
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        var body = new String(exchange.getRequestBody().readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);
        requests.add(new CapturedRequest(exchange.getRequestURI().getPath(), body));
        exchange.sendResponseHeaders(nextResponseCode.get(), -1);
        exchange.close();
    }

    private NotificationContext ctx(NotificationEventType eventType) {
        return new NotificationContext(
                eventType,
                UUID.randomUUID(), UUID.randomUUID(), QueryType.UPDATE,
                "UPDATE x", "UPDATE x", "UPDATE x",
                RiskLevel.CRITICAL, 95, "risky",
                UUID.randomUUID(), "Production",
                UUID.randomUUID(), "alice@example.com", "Alice",
                null, null, null, null,
                URI.create("https://app.example.test/queries/abc"),
                List.of(), Instant.now(), "en", null);
    }

    private record CapturedRequest(String path, String body) {
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
