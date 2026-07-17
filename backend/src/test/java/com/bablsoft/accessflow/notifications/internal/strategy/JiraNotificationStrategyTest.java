package com.bablsoft.accessflow.notifications.internal.strategy;

import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditEntry;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.core.api.QueryTicketService;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.RecordTicketCommand;
import com.bablsoft.accessflow.core.api.RiskLevel;
import com.bablsoft.accessflow.notifications.api.NotificationChannelType;
import com.bablsoft.accessflow.notifications.api.NotificationDeliveryException;
import com.bablsoft.accessflow.notifications.api.NotificationEventType;
import com.bablsoft.accessflow.notifications.internal.NotificationContext;
import com.bablsoft.accessflow.notifications.internal.codec.ChannelConfigCodec;
import com.bablsoft.accessflow.notifications.internal.codec.JiraChannelConfig;
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
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JiraNotificationStrategyTest {

    private static final String CREATE_RESPONSE = """
            {"id": "10001", "key": "SEC-42", "self": "https://example.atlassian.net/rest/api/2/issue/10001"}
            """;

    private HttpServer server;
    private final ConcurrentLinkedQueue<CapturedRequest> requests = new ConcurrentLinkedQueue<>();
    private AtomicInteger nextResponseCode;
    private ChannelConfigCodec codec;
    private NotificationChannelRepository channelRepository;
    private ServiceNowNotificationStrategyTest.RecordingTaskScheduler taskScheduler;
    private QueryTicketService queryTicketService;
    private AuditLogService auditLogService;
    private JiraNotificationStrategy strategy;
    private NotificationChannelEntity channel;
    private URI baseUrl;

    @BeforeEach
    void setUp() throws IOException {
        nextResponseCode = new AtomicInteger(201);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        baseUrl = URI.create("http://127.0.0.1:" + server.getAddress().getPort());

        codec = mock(ChannelConfigCodec.class);
        channelRepository = mock(NotificationChannelRepository.class);
        taskScheduler = new ServiceNowNotificationStrategyTest.RecordingTaskScheduler();
        queryTicketService = mock(QueryTicketService.class);
        auditLogService = mock(AuditLogService.class);
        var properties = new NotificationsProperties(
                URI.create("https://app.example.test"),
                new NotificationsProperties.Retry(
                        Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(3)),
                null, null);
        strategy = new JiraNotificationStrategy(
                codec, JsonMapper.builder().build(), RestClient.create(), taskScheduler,
                properties, channelRepository, mock(ApplicationEventPublisher.class),
                queryTicketService, auditLogService);

        channel = new NotificationChannelEntity();
        channel.setId(UUID.randomUUID());
        channel.setOrganizationId(UUID.randomUUID());
        channel.setChannelType(NotificationChannelType.JIRA);
        channel.setName("Jira");
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
        when(codec.decodeJira(anyString())).thenReturn(new JiraChannelConfig(
                baseUrl, "bot@example.com", "t0ken", "SEC", "Bug", triggers, true, "whsec",
                TicketingChannelConfig.DEFAULT_APPROVE_STATUSES,
                TicketingChannelConfig.DEFAULT_REJECT_STATUSES));
    }

    @Test
    void supportsJira() {
        assertThat(strategy.supports()).isEqualTo(NotificationChannelType.JIRA);
    }

    @Test
    void matchingTimeoutEventCreatesIssueAndRecordsTicket() {
        stubConfig(EnumSet.of(TicketingTrigger.REVIEW_TIMEOUT));
        var ctx = ctx(NotificationEventType.REVIEW_TIMEOUT);
        strategy.deliver(ctx, channel);

        assertThat(requests).hasSize(1);
        var request = requests.peek();
        assertThat(request.path).isEqualTo("/rest/api/2/issue");
        var expectedAuth = "Basic " + Base64.getEncoder()
                .encodeToString("bot@example.com:t0ken".getBytes(StandardCharsets.UTF_8));
        assertThat(request.authorization).isEqualTo(expectedAuth);
        assertThat(request.body).contains("\"project\":{\"key\":\"SEC\"}");
        assertThat(request.body).contains("\"issuetype\":{\"name\":\"Bug\"}");
        assertThat(request.body)
                .contains("\"summary\":\"[AccessFlow] Query review timed out on Production\"");
        assertThat(request.body).contains("\"labels\":[\"accessflow\"]");

        var captor = ArgumentCaptor.forClass(RecordTicketCommand.class);
        verify(queryTicketService).recordCreated(captor.capture());
        var command = captor.getValue();
        assertThat(command.ticketSystem()).isEqualTo("JIRA");
        assertThat(command.triggerEvent()).isEqualTo("REVIEW_TIMEOUT");
        assertThat(command.externalId()).isEqualTo("10001");
        assertThat(command.externalKey()).isEqualTo("SEC-42");
        assertThat(command.url()).isEqualTo(baseUrl + "/browse/SEC-42");
        assertThat(command.status()).isEqualTo(JiraNotificationStrategy.INITIAL_STATUS);

        var auditCaptor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditLogService).record(auditCaptor.capture());
        assertThat(auditCaptor.getValue().action()).isEqualTo(AuditAction.TICKET_CREATED);
    }

    @Test
    void nonMatchingTriggerDoesNotFire() {
        stubConfig(EnumSet.of(TicketingTrigger.QUERY_ESCALATED));
        strategy.deliver(ctx(NotificationEventType.QUERY_REJECTED), channel);
        assertThat(requests).isEmpty();
    }

    @Test
    void existingTicketIsNotDuplicated() {
        stubConfig(EnumSet.of(TicketingTrigger.REVIEW_TIMEOUT));
        when(queryTicketService.existsFor(any(), any(), anyString())).thenReturn(true);
        strategy.deliver(ctx(NotificationEventType.REVIEW_TIMEOUT), channel);
        assertThat(requests).isEmpty();
    }

    @Test
    void createFailureSchedulesRetry() {
        stubConfig(EnumSet.of(TicketingTrigger.REVIEW_TIMEOUT));
        nextResponseCode.set(500);
        strategy.deliver(ctx(NotificationEventType.REVIEW_TIMEOUT), channel);
        assertThat(taskScheduler.scheduled).hasSize(1);
        verify(queryTicketService, times(0)).recordCreated(any());
    }

    @Test
    void responseMissingIdAndKeyRecordsNothing() {
        stubConfig(EnumSet.of(TicketingTrigger.REVIEW_TIMEOUT));
        nextResponseCode.set(-2);
        strategy.deliver(ctx(NotificationEventType.REVIEW_TIMEOUT), channel);
        assertThat(requests).hasSize(1);
        verify(queryTicketService, times(0)).recordCreated(any());
    }

    @Test
    void sendTestProbesMyselfEndpoint() {
        stubConfig(EnumSet.noneOf(TicketingTrigger.class));
        nextResponseCode.set(200);
        strategy.sendTest(channel, null);
        assertThat(requests).hasSize(1);
        assertThat(requests.peek().method).isEqualTo("GET");
        assertThat(requests.peek().path).isEqualTo("/rest/api/2/myself");
    }

    @Test
    void sendTestPropagatesAuthFailure() {
        stubConfig(EnumSet.noneOf(TicketingTrigger.class));
        nextResponseCode.set(403);
        assertThatThrownBy(() -> strategy.sendTest(channel, null))
                .isInstanceOf(NotificationDeliveryException.class)
                .hasMessageContaining("403");
    }

    private void handle(HttpExchange exchange) throws IOException {
        var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        requests.add(new CapturedRequest(
                exchange.getRequestMethod(),
                exchange.getRequestURI().getPath(),
                exchange.getRequestHeaders().getFirst("Authorization"),
                body));
        var code = nextResponseCode.get();
        if (code == -2) {
            var empty = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(201, empty.length);
            exchange.getResponseBody().write(empty);
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
                UUID.randomUUID(), UUID.randomUUID(), QueryType.DELETE,
                "DELETE FROM x", "DELETE FROM x", "DELETE FROM x",
                RiskLevel.CRITICAL, 90, "very risky",
                UUID.randomUUID(), "Production",
                UUID.randomUUID(), "bob@example.com", "Bob",
                "cleanup", null, null, null,
                URI.create("https://app.example.test/queries/xyz"),
                List.of(), Instant.now(), "en", 24);
    }

    private record CapturedRequest(String method, String path, String authorization, String body) {
    }
}
