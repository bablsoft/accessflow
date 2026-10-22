package com.bablsoft.accessflow.deploygov;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.deploygov.api.DeploymentTicketService;
import com.bablsoft.accessflow.deploygov.api.PipelineProvider;
import com.bablsoft.accessflow.deploygov.api.RecordDeploymentTicketCommand;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentEnvironmentEntity;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentPipelineEntity;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentRequestEntity;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentEnvironmentRepository;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentPipelineRepository;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentRequestRepository;
import com.bablsoft.accessflow.notifications.api.CreateNotificationChannelCommand;
import com.bablsoft.accessflow.notifications.api.NotificationChannelService;
import com.bablsoft.accessflow.notifications.api.NotificationChannelType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #992 end to end: a correctly signed Jira status callback for a deployment change ticket stamps
 * the {@code deployment_tickets} decision on every verified callback and — only with
 * {@code bidirectional_sync} — decides the still-pending deployment, system-attributed.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ImportTestcontainers(TestcontainersConfig.class)
class DeploymentTicketWebhookIntegrationTest {

    private static final String SECRET = "it-webhook-secret";

    @LocalServerPort
    private int port;
    @Autowired
    private DeploymentPipelineRepository pipelineRepository;
    @Autowired
    private DeploymentEnvironmentRepository environmentRepository;
    @Autowired
    private DeploymentRequestRepository requestRepository;
    @Autowired
    private DeploymentTicketService ticketService;
    @Autowired
    private NotificationChannelService channelService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final HttpClient http = HttpClient.newHttpClient();

    private UUID organizationId;
    private DeploymentRequestEntity request;

    @BeforeEach
    void seed() {
        organizationId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO organizations (id, name, slug) VALUES (?, ?, ?)",
                organizationId, "ticket-webhook-it", "ticket-webhook-it-" + organizationId);

        var pipeline = new DeploymentPipelineEntity();
        pipeline.setId(UUID.randomUUID());
        pipeline.setOrganizationId(organizationId);
        pipeline.setName("payments-api-" + UUID.randomUUID());
        pipeline.setProvider(PipelineProvider.GENERIC);
        pipelineRepository.saveAndFlush(pipeline);

        var environment = new DeploymentEnvironmentEntity();
        environment.setId(UUID.randomUUID());
        environment.setPipelineId(pipeline.getId());
        environment.setName("production");
        environment.setSortOrder(0);
        environment = environmentRepository.saveAndFlush(environment);

        request = new DeploymentRequestEntity();
        request.setId(UUID.randomUUID());
        request.setPipelineId(pipeline.getId());
        request.setEnvironmentId(environment.getId());
        request.setOrganizationId(organizationId);
        request.setSubmittedBy(UUID.randomUUID());
        request.setVersion("2.4.1");
        request.setStatus(QueryStatus.PENDING_REVIEW);
        request = requestRepository.saveAndFlush(request);
    }

    @Test
    void syncOnApprovesPendingDeploymentAndStampsDecision() throws Exception {
        var channelId = jiraChannel(true);
        var externalId = linkTicket(channelId);

        var response = post(channelId, externalId, "Done");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("decision_applied");
        assertThat(ticketDecision(channelId)).isEqualTo("APPROVED");
        assertThat(requestRepository.findById(request.getId()).orElseThrow().getStatus())
                .isEqualTo(QueryStatus.APPROVED);

        var synced = auditRow("TICKET_STATUS_SYNCED");
        assertThat(synced.get("resource_type")).isEqualTo("deployment_ticket");
        assertThat(synced.get("actor_id")).isNull();
        assertThat((String) synced.get("metadata")).contains("\"decision\": \"APPROVED\"");

        var approved = auditRow("DEPLOYMENT_APPROVED");
        assertThat(approved.get("resource_type")).isEqualTo("deployment_request");
        assertThat(approved.get("actor_id")).isNull();
        assertThat((String) approved.get("metadata"))
                .contains("\"trigger\": \"ticket\"")
                .contains("\"ticket_system\": \"JIRA\"")
                .contains("\"actor\": \"jdoe\"");
    }

    @Test
    void syncOffStampsDecisionButLeavesDeploymentPending() throws Exception {
        var channelId = jiraChannel(false);
        var externalId = linkTicket(channelId);

        var response = post(channelId, externalId, "Done");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("synced");
        assertThat(ticketDecision(channelId)).isEqualTo("APPROVED");
        assertThat(requestRepository.findById(request.getId()).orElseThrow().getStatus())
                .isEqualTo(QueryStatus.PENDING_REVIEW);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE organization_id = ? AND action = ?",
                Integer.class, organizationId, "DEPLOYMENT_APPROVED")).isZero();
    }

    private UUID jiraChannel(boolean bidirectionalSync) {
        var config = new HashMap<String, Object>();
        config.put("base_url", "https://example.atlassian.net");
        config.put("user_email", "bot@example.com");
        config.put("api_token", "token");
        config.put("project_key", "OPS");
        config.put("triggers", List.of("DEPLOYMENT_SUBMITTED"));
        config.put("bidirectional_sync", bidirectionalSync);
        config.put("webhook_secret", SECRET);
        return channelService.create(new CreateNotificationChannelCommand(organizationId,
                NotificationChannelType.JIRA, "Jira " + UUID.randomUUID(), config)).id();
    }

    private String linkTicket(UUID channelId) {
        var externalId = "10" + Math.abs(UUID.randomUUID().getMostSignificantBits() % 100000);
        ticketService.recordCreated(new RecordDeploymentTicketCommand(organizationId,
                request.getId(), channelId, "JIRA", "DEPLOYMENT_SUBMITTED", externalId,
                "OPS-" + externalId, null, "To Do"));
        return externalId;
    }

    private HttpResponse<String> post(UUID channelId, String externalId, String status)
            throws Exception {
        var body = "{\"external_id\":\"" + externalId + "\",\"status\":\"" + status
                + "\",\"actor\":\"jdoe\"}";
        var timestamp = String.valueOf(Instant.now().getEpochSecond());
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        var signature = "sha256=" + HexFormat.of().formatHex(
                mac.doFinal(("v1:" + timestamp + ":" + body).getBytes(StandardCharsets.UTF_8)));
        var httpRequest = HttpRequest.newBuilder(URI.create("http://localhost:" + port
                        + "/api/v1/integrations/jira/webhook/" + channelId))
                .header("Content-Type", "application/json")
                .header("X-AccessFlow-Timestamp", timestamp)
                .header("X-AccessFlow-Signature", signature)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return http.send(httpRequest, HttpResponse.BodyHandlers.ofString());
    }

    private String ticketDecision(UUID channelId) {
        return ticketService.listByDeploymentRequest(request.getId(), organizationId).stream()
                .filter(ticket -> ticket.channelId().equals(channelId))
                .findFirst().orElseThrow().decision();
    }

    private Map<String, Object> auditRow(String action) {
        return jdbcTemplate.queryForMap("SELECT resource_type, actor_id, metadata::text AS metadata"
                        + " FROM audit_log WHERE organization_id = ? AND action = ?",
                organizationId, action);
    }
}
