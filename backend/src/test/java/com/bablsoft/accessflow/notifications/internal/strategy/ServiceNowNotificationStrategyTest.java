package com.bablsoft.accessflow.notifications.internal.strategy;

import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditEntry;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import com.bablsoft.accessflow.audit.events.NotificationDeliveryExhaustedEvent;
import com.bablsoft.accessflow.core.api.QueryTicketService;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.RecordTicketCommand;
import com.bablsoft.accessflow.core.api.RiskLevel;
import com.bablsoft.accessflow.deploygov.api.DeploymentTicketService;
import com.bablsoft.accessflow.deploygov.api.RecordDeploymentTicketCommand;
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
import org.springframework.dao.DataIntegrityViolationException;
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
    private DeploymentTicketService deploymentTicketService;
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
        deploymentTicketService = mock(DeploymentTicketService.class);
        auditLogService = mock(AuditLogService.class);
        var properties = new NotificationsProperties(
                URI.create("https://app.example.test"),
                new NotificationsProperties.Retry(
                        Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(3)),
                null, null);
        strategy = new ServiceNowNotificationStrategy(
                codec, JsonMapper.builder().build(), RestClient.create(), taskScheduler,
                properties, channelRepository, eventPublisher, queryTicketService,
                deploymentTicketService, auditLogService);

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
    void flaggedDeploymentSubmissionOpensAChangeTicket() {
        stubConfig(EnumSet.of(TicketingTrigger.DEPLOYMENT_SUBMITTED));
        var ctx = deploymentCtx(NotificationEventType.DEPLOYMENT_SUBMITTED, true);
        strategy.deliver(ctx, channel);

        assertThat(requests).hasSize(1);
        var body = requests.peek().body;
        assertThat(body).contains("\"short_description\":\"[AccessFlow] Deployment 2.4.1 to "
                + "production awaiting approval (payments)\"");
        assertThat(body).contains("\"correlation_id\":\"accessflow-deployment-"
                + ctx.deploymentRequestId() + "\"");

        var captor = ArgumentCaptor.forClass(RecordDeploymentTicketCommand.class);
        verify(deploymentTicketService).recordCreated(captor.capture());
        var command = captor.getValue();
        assertThat(command.deploymentRequestId()).isEqualTo(ctx.deploymentRequestId());
        assertThat(command.organizationId()).isEqualTo(ctx.organizationId());
        assertThat(command.channelId()).isEqualTo(channel.getId());
        assertThat(command.ticketSystem()).isEqualTo("SERVICENOW");
        assertThat(command.triggerEvent()).isEqualTo("DEPLOYMENT_SUBMITTED");
        assertThat(command.externalId()).isEqualTo("abc123");
        assertThat(command.externalKey()).isEqualTo("INC0010023");
        assertThat(command.status()).isEqualTo(ServiceNowNotificationStrategy.INITIAL_STATUS);
        verify(queryTicketService, times(0)).recordCreated(any());

        var auditCaptor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditLogService).record(auditCaptor.capture());
        var audit = auditCaptor.getValue();
        assertThat(audit.action()).isEqualTo(AuditAction.TICKET_CREATED);
        assertThat(audit.resourceType()).isEqualTo(AuditResourceType.DEPLOYMENT_TICKET);
        assertThat(audit.resourceId()).isEqualTo(ctx.deploymentRequestId());
        assertThat(audit.metadata())
                .containsEntry("deployment_request_id", ctx.deploymentRequestId().toString())
                .containsEntry("trigger_event", "DEPLOYMENT_SUBMITTED")
                .doesNotContainKey("query_request_id");
    }

    @Test
    void autoApprovedDeploymentOpensTheTicketOnApproval() {
        stubConfig(EnumSet.of(TicketingTrigger.DEPLOYMENT_SUBMITTED));
        strategy.deliver(deploymentCtx(NotificationEventType.DEPLOYMENT_APPROVED, true), channel);

        assertThat(requests).hasSize(1);
        var captor = ArgumentCaptor.forClass(RecordDeploymentTicketCommand.class);
        verify(deploymentTicketService).recordCreated(captor.capture());
        assertThat(captor.getValue().triggerEvent()).isEqualTo("DEPLOYMENT_APPROVED");
    }

    @Test
    void unflaggedEnvironmentOpensNoTicket() {
        stubConfig(EnumSet.allOf(TicketingTrigger.class));
        strategy.deliver(deploymentCtx(NotificationEventType.DEPLOYMENT_SUBMITTED, false),
                channel);
        assertThat(requests).isEmpty();
        verifyNoInteractions(deploymentTicketService, auditLogService);
    }

    @Test
    void deploymentWithoutDetailsOpensNoTicket() {
        stubConfig(EnumSet.allOf(TicketingTrigger.class));
        strategy.deliver(deploymentCtx(NotificationEventType.DEPLOYMENT_SUBMITTED, (Boolean) null),
                channel);
        assertThat(requests).isEmpty();
    }

    @Test
    void deploymentTriggerOffOpensNoTicket() {
        stubConfig(EnumSet.of(TicketingTrigger.QUERY_REJECTED));
        strategy.deliver(deploymentCtx(NotificationEventType.DEPLOYMENT_SUBMITTED, true), channel);
        assertThat(requests).isEmpty();
    }

    @Test
    void deploymentLifecycleEventsBeyondTheOpeningOnesOpenNoTicket() {
        stubConfig(EnumSet.allOf(TicketingTrigger.class));
        strategy.deliver(deploymentCtx(NotificationEventType.DEPLOYMENT_REJECTED, true), channel);
        strategy.deliver(deploymentCtx(NotificationEventType.DEPLOYMENT_BREAK_GLASS_EXECUTED, true),
                channel);
        assertThat(requests).isEmpty();
    }

    @Test
    void existingDeploymentTicketIsNotDuplicated() {
        stubConfig(EnumSet.of(TicketingTrigger.DEPLOYMENT_SUBMITTED));
        var ctx = deploymentCtx(NotificationEventType.DEPLOYMENT_APPROVED, true);
        when(deploymentTicketService.existsFor(channel.getId(), ctx.deploymentRequestId()))
                .thenReturn(true);
        strategy.deliver(ctx, channel);
        assertThat(requests).isEmpty();
        verify(queryTicketService, times(0)).existsFor(any(), any(), anyString());
    }

    @Test
    void racedDeploymentTicketLinkWritesNoAudit() {
        stubConfig(EnumSet.of(TicketingTrigger.DEPLOYMENT_SUBMITTED));
        when(deploymentTicketService.recordCreated(any()))
                .thenThrow(new DataIntegrityViolationException("uq_deployment_tickets"));
        strategy.deliver(deploymentCtx(NotificationEventType.DEPLOYMENT_SUBMITTED, true), channel);
        assertThat(requests).hasSize(1);
        verifyNoInteractions(auditLogService);
    }

    @Test
    void racedQueryTicketLinkWritesNoAudit() {
        stubConfig(EnumSet.of(TicketingTrigger.QUERY_REJECTED));
        when(queryTicketService.recordCreated(any()))
                .thenThrow(new DataIntegrityViolationException("uq_query_tickets"));
        strategy.deliver(ctx(NotificationEventType.QUERY_REJECTED), channel);
        assertThat(requests).hasSize(1);
        verifyNoInteractions(auditLogService);
    }

    @Test
    void auditFailureDoesNotPropagate() {
        stubConfig(EnumSet.of(TicketingTrigger.DEPLOYMENT_SUBMITTED));
        when(auditLogService.record(any())).thenThrow(new IllegalStateException("audit down"));
        strategy.deliver(deploymentCtx(NotificationEventType.DEPLOYMENT_SUBMITTED, true), channel);
        verify(deploymentTicketService).recordCreated(any());
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

    /**
     * A deployment context (#991) on environment {@code production} of pipeline {@code payments};
     * {@code changeTicketRequired == null} omits the deployment details entirely.
     */
    static NotificationContext deploymentCtx(NotificationEventType eventType,
                                             Boolean changeTicketRequired) {
        var details = changeTicketRequired == null ? null
                : new NotificationContext.DeploymentDetails("abc123",
                        "ghcr.io/acme/payments:2.4.1", "https://ci.example/runs/42",
                        changeTicketRequired, null);
        return deploymentCtx(eventType, details);
    }

    static NotificationContext deploymentCtx(NotificationEventType eventType,
                                             NotificationContext.DeploymentDetails details) {
        var deploymentRequestId = UUID.randomUUID();
        return new NotificationContext(
                eventType,
                UUID.randomUUID(), null, null,
                null, null, null,
                RiskLevel.MEDIUM, 45, "moderate",
                UUID.randomUUID(), "payments",
                UUID.randomUUID(), "alice@example.com", "Alice",
                "release train 42", null, null, null,
                URI.create("https://app.example.test/deployments/" + deploymentRequestId),
                List.of(), Instant.now(), "en", null,
                null, null, null, null, null, null,
                null,
                null, null, null,
                null,
                null, null, null,
                null, null, null,
                null, null, null,
                deploymentRequestId, "production", "2.4.1", null, null,
                null, null, null, null, null,
                null,
                details);
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
