package com.bablsoft.accessflow.notifications.internal.strategy;

import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditEntry;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.audit.events.NotificationDeliveryExhaustedEvent;
import com.bablsoft.accessflow.core.api.QueryTicketService;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.RecordTicketCommand;
import com.bablsoft.accessflow.core.api.RiskLevel;
import com.bablsoft.accessflow.notifications.api.NotificationChannelType;
import com.bablsoft.accessflow.notifications.api.NotificationDeliveryException;
import com.bablsoft.accessflow.notifications.api.NotificationEventType;
import com.bablsoft.accessflow.notifications.internal.NotificationContext;
import com.bablsoft.accessflow.notifications.internal.codec.ChannelConfigCodec;
import com.bablsoft.accessflow.notifications.internal.codec.ServiceNowChannelConfig;
import com.bablsoft.accessflow.notifications.internal.codec.TicketingChannelConfig;
import com.bablsoft.accessflow.notifications.internal.codec.TicketingTrigger;
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
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ServiceNowNotificationStrategyTest {

    private static final String CREATE_RESPONSE = """
            {"result": {"sys_id": "abc123", "number": "INC0010023", "state": "1"}}
            """;

    private HttpServer server;
    private final ConcurrentLinkedQueue<CapturedRequest> requests = new ConcurrentLinkedQueue<>();
    private AtomicInteger nextResponseCode;
    private ChannelConfigCodec codec;
    private NotificationChannelRepository channelRepository;
    private RecordingTaskScheduler taskScheduler;
    private ApplicationEventPublisher eventPublisher;
    private QueryTicketService queryTicketService;
    private AuditLogService auditLogService;
    private ServiceNowNotificationStrategy strategy;
    private NotificationChannelEntity channel;
    private URI instanceUrl;

    @BeforeEach
    void setUp() throws IOException {
        nextResponseCode = new AtomicInteger(201);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        instanceUrl = URI.create("http://127.0.0.1:" + server.getAddress().getPort());

        codec = mock(ChannelConfigCodec.class);
        channelRepository = mock(NotificationChannelRepository.class);
        taskScheduler = new RecordingTaskScheduler();
        eventPublisher = mock(ApplicationEventPublisher.class);
        queryTicketService = mock(QueryTicketService.class);
        auditLogService = mock(AuditLogService.class);
        var properties = new NotificationsProperties(
                URI.create("https://app.example.test"),
                new NotificationsProperties.Retry(
                        Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(3)),
                null, null);
        strategy = new ServiceNowNotificationStrategy(
                codec, JsonMapper.builder().build(), RestClient.create(), taskScheduler,
                properties, channelRepository, eventPublisher, queryTicketService,
                auditLogService);

        channel = new NotificationChannelEntity();
        channel.setId(UUID.randomUUID());
        channel.setOrganizationId(UUID.randomUUID());
        channel.setChannelType(NotificationChannelType.SERVICENOW);
        channel.setName("ServiceNow");
        channel.setActive(true);
        channel.setConfigJson("{}");
        channel.setCreatedAt(Instant.now());

        when(channelRepository.findById(channel.getId())).thenReturn(Optional.of(channel));
        when(queryTicketService.existsFor(any(), any(), anyString())).thenReturn(false);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private void stubConfig(Set<TicketingTrigger> triggers) {
        when(codec.decodeServiceNow(anyString())).thenReturn(new ServiceNowChannelConfig(
                instanceUrl, "integration", "s3cret", "DB Ops", 2, triggers, false, null,
                TicketingChannelConfig.DEFAULT_APPROVE_STATUSES,
                TicketingChannelConfig.DEFAULT_REJECT_STATUSES));
    }

    @Test
    void supportsServiceNow() {
        assertThat(strategy.supports()).isEqualTo(NotificationChannelType.SERVICENOW);
    }

    @Test
    void matchingRejectedEventCreatesIncidentAndRecordsTicket() {
        stubConfig(EnumSet.of(TicketingTrigger.QUERY_REJECTED));
        var ctx = ctx(NotificationEventType.QUERY_REJECTED);
        strategy.deliver(ctx, channel);

        assertThat(requests).hasSize(1);
        var request = requests.peek();
        assertThat(request.path).isEqualTo("/api/now/table/incident");
        assertThat(request.authorization).startsWith("Basic ");
        assertThat(request.body).contains("\"short_description\":\"[AccessFlow] Query rejected on Production\"");
        assertThat(request.body).contains("\"urgency\":\"2\"");
        assertThat(request.body).contains("\"assignment_group\":\"DB Ops\"");
        assertThat(request.body).contains("\"correlation_id\":\"accessflow-" + ctx.queryRequestId() + "\"");

        var captor = ArgumentCaptor.forClass(RecordTicketCommand.class);
        verify(queryTicketService).recordCreated(captor.capture());
        var command = captor.getValue();
        assertThat(command.ticketSystem()).isEqualTo("SERVICENOW");
        assertThat(command.triggerEvent()).isEqualTo("QUERY_REJECTED");
        assertThat(command.externalId()).isEqualTo("abc123");
        assertThat(command.externalKey()).isEqualTo("INC0010023");
        assertThat(command.url()).contains("sys_id=abc123");
        assertThat(command.status()).isEqualTo(ServiceNowNotificationStrategy.INITIAL_STATUS);
        assertThat(command.queryRequestId()).isEqualTo(ctx.queryRequestId());
        assertThat(command.organizationId()).isEqualTo(ctx.organizationId());

        var auditCaptor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditLogService).record(auditCaptor.capture());
        assertThat(auditCaptor.getValue().action()).isEqualTo(AuditAction.TICKET_CREATED);
        assertThat(auditCaptor.getValue().metadata()).containsEntry("external_key", "INC0010023");
    }

    @Test
    void escalatedTriggerMapsToQueryEscalatedEvent() {
        stubConfig(EnumSet.of(TicketingTrigger.QUERY_ESCALATED));
        strategy.deliver(ctx(NotificationEventType.QUERY_ESCALATED), channel);
        assertThat(requests).hasSize(1);
    }

    @Test
    void nonMatchingTriggerDoesNotFire() {
        stubConfig(EnumSet.of(TicketingTrigger.REVIEW_TIMEOUT));
        strategy.deliver(ctx(NotificationEventType.QUERY_REJECTED), channel);
        assertThat(requests).isEmpty();
        verifyNoInteractions(auditLogService);
    }

    @Test
    void unmappedEventDoesNotFire() {
        stubConfig(EnumSet.allOf(TicketingTrigger.class));
        strategy.deliver(ctx(NotificationEventType.QUERY_APPROVED), channel);
        assertThat(requests).isEmpty();
    }

    @Test
    void existingTicketIsNotDuplicated() {
        stubConfig(EnumSet.of(TicketingTrigger.QUERY_REJECTED));
        when(queryTicketService.existsFor(any(), any(), anyString())).thenReturn(true);
        strategy.deliver(ctx(NotificationEventType.QUERY_REJECTED), channel);
        assertThat(requests).isEmpty();
    }

    @Test
    void nonQueryContextIsSkipped() {
        stubConfig(EnumSet.allOf(TicketingTrigger.class));
        strategy.deliver(anomalyCtx(), channel);
        assertThat(requests).isEmpty();
    }

    @Test
    void transient5xxSchedulesRetryAndSucceedsOnSecondAttempt() {
        stubConfig(EnumSet.of(TicketingTrigger.QUERY_REJECTED));
        nextResponseCode.set(500);
        strategy.deliver(ctx(NotificationEventType.QUERY_REJECTED), channel);

        assertThat(taskScheduler.scheduled).hasSize(1);
        verify(queryTicketService, times(0)).recordCreated(any());

        nextResponseCode.set(201);
        runAllScheduled();
        assertThat(requests).hasSize(2);
        verify(queryTicketService, times(1)).recordCreated(any());
    }

    @Test
    void exhaustedRetriesPublishExhaustedEventAndRecordNothing() {
        stubConfig(EnumSet.of(TicketingTrigger.QUERY_REJECTED));
        nextResponseCode.set(503);
        strategy.deliver(ctx(NotificationEventType.QUERY_REJECTED), channel);
        runAllScheduled();
        runAllScheduled();
        runAllScheduled();
        runAllScheduled();

        var captor = ArgumentCaptor.forClass(NotificationDeliveryExhaustedEvent.class);
        verify(eventPublisher, times(1)).publishEvent(captor.capture());
        assertThat(captor.getValue().channelType())
                .isEqualTo(NotificationChannelType.SERVICENOW.name());
        assertThat(captor.getValue().attemptCount()).isEqualTo(4);
        verify(queryTicketService, times(0)).recordCreated(any());
    }

    @Test
    void unparseableCreateResponseRecordsNothing() {
        stubConfig(EnumSet.of(TicketingTrigger.QUERY_REJECTED));
        nextResponseCode.set(-1);
        strategy.deliver(ctx(NotificationEventType.QUERY_REJECTED), channel);
        assertThat(requests).hasSize(1);
        verify(queryTicketService, times(0)).recordCreated(any());
        verifyNoInteractions(auditLogService);
    }

    @Test
    void retryStopsWhenChannelDeactivated() {
        stubConfig(EnumSet.of(TicketingTrigger.QUERY_REJECTED));
        nextResponseCode.set(500);
        strategy.deliver(ctx(NotificationEventType.QUERY_REJECTED), channel);
        channel.setActive(false);
        runAllScheduled();
        assertThat(requests).hasSize(1);
    }

    @Test
    void sendTestProbesIncidentTableReadOnly() {
        stubConfig(EnumSet.noneOf(TicketingTrigger.class));
        nextResponseCode.set(200);
        strategy.sendTest(channel, null);
        assertThat(requests).hasSize(1);
        assertThat(requests.peek().method).isEqualTo("GET");
        assertThat(requests.peek().path).isEqualTo("/api/now/table/incident");
        verifyNoInteractions(queryTicketService);
    }

    @Test
    void sendTestPropagatesAuthFailure() {
        stubConfig(EnumSet.noneOf(TicketingTrigger.class));
        nextResponseCode.set(401);
        assertThatThrownBy(() -> strategy.sendTest(channel, null))
                .isInstanceOf(NotificationDeliveryException.class)
                .hasMessageContaining("401");
    }

    private void runAllScheduled() {
        var pending = new java.util.ArrayList<>(taskScheduler.scheduled);
        taskScheduler.scheduled.clear();
        for (var s : pending) {
            s.task().run();
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        requests.add(new CapturedRequest(
                exchange.getRequestMethod(),
                exchange.getRequestURI().getPath(),
                exchange.getRequestHeaders().getFirst("Authorization"),
                body));
        var code = nextResponseCode.get();
        if (code == -1) {
            var garbage = "not-json".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(201, garbage.length);
            exchange.getResponseBody().write(garbage);
        } else if (code >= 200 && code < 300 && !"GET".equals(exchange.getRequestMethod())) {
            var response = CREATE_RESPONSE.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(code, response.length);
            exchange.getResponseBody().write(response);
        } else {
            exchange.sendResponseHeaders(code, -1);
        }
        exchange.close();
    }

    private NotificationContext ctx(NotificationEventType eventType) {
        return new NotificationContext(
                eventType,
                UUID.randomUUID(), UUID.randomUUID(), QueryType.UPDATE,
                "UPDATE x", "UPDATE x", "UPDATE x",
                RiskLevel.HIGH, 80, "risky",
                UUID.randomUUID(), "Production",
                UUID.randomUUID(), "alice@example.com", "Alice",
                "cleanup", null, null, "not ok",
                URI.create("https://app.example.test/queries/abc"),
                List.of(), Instant.now(), "en", null);
    }

    private NotificationContext anomalyCtx() {
        return new NotificationContext(
                NotificationEventType.QUERY_REJECTED,
                UUID.randomUUID(), null, null,
                null, null, null,
                null, null, null,
                null, null,
                null, null, null,
                null, null, null, null,
                null, List.of(), Instant.now(), "en", null);
    }

    private record CapturedRequest(String method, String path, String authorization, String body) {
    }

    static final class RecordingTaskScheduler implements TaskScheduler {
        record Scheduled(Runnable task, Instant when) {
        }

        final List<Scheduled> scheduled = new CopyOnWriteArrayList<>();

        @Override
        public ScheduledFuture<?> schedule(Runnable task, Instant startTime) {
            scheduled.add(new Scheduled(task, startTime));
            return null;
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable task,
                org.springframework.scheduling.Trigger trigger) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Instant startTime,
                Duration period) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Duration period) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Instant startTime,
                Duration delay) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Duration delay) {
            throw new UnsupportedOperationException();
        }
    }
}
