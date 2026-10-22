package com.bablsoft.accessflow.deploygov;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.deploygov.api.DeploymentTicketService;
import com.bablsoft.accessflow.deploygov.api.PipelineProvider;
import com.bablsoft.accessflow.deploygov.api.RecordDeploymentTicketCommand;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentEnvironmentEntity;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentPipelineEntity;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentRequestEntity;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentTicketEntity;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentEnvironmentRepository;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentPipelineRepository;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentRequestRepository;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentTicketRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V202 (#990) under {@code ddl-auto=validate}: the {@code deployment_tickets} entity maps to its
 * table, both UNIQUE constraints hold, the request FK cascades, and the environment's
 * {@code change_ticket_required} column round-trips.
 */
@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class DeploymentTicketPersistenceIntegrationTest {

    @Autowired
    private DeploymentPipelineRepository pipelineRepository;
    @Autowired
    private DeploymentEnvironmentRepository environmentRepository;
    @Autowired
    private DeploymentRequestRepository requestRepository;
    @Autowired
    private DeploymentTicketRepository ticketRepository;
    @Autowired
    private DeploymentTicketService ticketService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID organizationId;
    private DeploymentEnvironmentEntity environment;
    private DeploymentRequestEntity request;

    @BeforeEach
    void seed() {
        organizationId = UUID.randomUUID();
        var pipeline = new DeploymentPipelineEntity();
        pipeline.setId(UUID.randomUUID());
        pipeline.setOrganizationId(organizationId);
        pipeline.setName("payments-api-" + UUID.randomUUID());
        pipeline.setProvider(PipelineProvider.GENERIC);
        pipelineRepository.saveAndFlush(pipeline);

        environment = new DeploymentEnvironmentEntity();
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
        request = requestRepository.saveAndFlush(request);
    }

    @Test
    void changeTicketRequiredDefaultsFalseAndRoundTrips() {
        assertThat(environmentRepository.findById(environment.getId()).orElseThrow()
                .isChangeTicketRequired()).isFalse();

        var reloaded = environmentRepository.findById(environment.getId()).orElseThrow();
        reloaded.setChangeTicketRequired(true);
        environmentRepository.saveAndFlush(reloaded);

        assertThat(environmentRepository.findById(environment.getId()).orElseThrow()
                .isChangeTicketRequired()).isTrue();
    }

    @Test
    void recordsReloadsAndStampsTheDecisionThroughTheService() {
        var channelId = UUID.randomUUID();
        var recorded = ticketService.recordCreated(new RecordDeploymentTicketCommand(
                organizationId, request.getId(), channelId, "SERVICENOW", "DEPLOYMENT_SUBMITTED",
                "sys-" + UUID.randomUUID(), "CHG0030012", "https://sn.example/chg/1", "New"));

        assertThat(ticketService.existsFor(channelId, request.getId())).isTrue();
        var updated = ticketService.updateStatus(channelId, recorded.externalId(), "Implement",
                null, "APPROVED").orElseThrow();
        assertThat(updated.decision()).isEqualTo("APPROVED");
        assertThat(updated.decidedAt()).isNotNull();

        // Sticky approval: a later unmapped status (null decision) keeps APPROVED and decidedAt.
        var progressed = ticketService.updateStatus(channelId, recorded.externalId(), "Review",
                null, null).orElseThrow();
        assertThat(progressed.status()).isEqualTo("Review");
        assertThat(progressed.decision()).isEqualTo("APPROVED");
        // Postgres keeps microseconds; the first view may carry the clock's nanoseconds (Linux).
        assertThat(progressed.decidedAt().truncatedTo(ChronoUnit.MICROS))
                .isEqualTo(updated.decidedAt().truncatedTo(ChronoUnit.MICROS));

        var listed = ticketService.listByDeploymentRequest(request.getId(), organizationId);
        assertThat(listed).singleElement().satisfies(view -> {
            assertThat(view.externalKey()).isEqualTo("CHG0030012");
            assertThat(view.status()).isEqualTo("Review");
            assertThat(view.decision()).isEqualTo("APPROVED");
        });
        assertThat(ticketService.listByDeploymentRequest(request.getId(), UUID.randomUUID()))
                .isEmpty();
        assertThat(ticketRepository.findAllByDeploymentRequestId(request.getId())).hasSize(1);
    }

    @Test
    void oneTicketPerChannelAndRequest() {
        var channelId = UUID.randomUUID();
        ticketRepository.saveAndFlush(ticket(channelId, "ext-" + UUID.randomUUID()));

        assertThatThrownBy(() -> ticketRepository.saveAndFlush(
                ticket(channelId, "ext-" + UUID.randomUUID())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void externalIdIsUniquePerChannel() {
        var channelId = UUID.randomUUID();
        var externalId = "ext-" + UUID.randomUUID();
        ticketRepository.saveAndFlush(ticket(channelId, externalId));

        var otherRequest = new DeploymentRequestEntity();
        otherRequest.setId(UUID.randomUUID());
        otherRequest.setPipelineId(request.getPipelineId());
        otherRequest.setEnvironmentId(environment.getId());
        otherRequest.setOrganizationId(organizationId);
        otherRequest.setSubmittedBy(UUID.randomUUID());
        otherRequest.setVersion("2.4.2");
        requestRepository.saveAndFlush(otherRequest);
        var duplicate = ticket(channelId, externalId);
        duplicate.setDeploymentRequestId(otherRequest.getId());

        assertThatThrownBy(() -> ticketRepository.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void decisionAcceptsOnlyApprovedOrRejected() {
        var ticket = ticket(UUID.randomUUID(), "ext-" + UUID.randomUUID());
        ticket.setDecision("approved");

        assertThatThrownBy(() -> ticketRepository.saveAndFlush(ticket))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void ticketsCascadeWithTheirDeploymentRequest() {
        var saved = ticketRepository.saveAndFlush(ticket(UUID.randomUUID(),
                "ext-" + UUID.randomUUID()));

        jdbcTemplate.update("DELETE FROM deployment_requests WHERE id = ?", request.getId());

        assertThat(ticketRepository.findById(saved.getId())).isEmpty();
    }

    private DeploymentTicketEntity ticket(UUID channelId, String externalId) {
        var now = Instant.now();
        var ticket = new DeploymentTicketEntity();
        ticket.setId(UUID.randomUUID());
        ticket.setOrganizationId(organizationId);
        ticket.setDeploymentRequestId(request.getId());
        ticket.setChannelId(channelId);
        ticket.setTicketSystem("JIRA");
        ticket.setTriggerEvent("DEPLOYMENT_SUBMITTED");
        ticket.setExternalId(externalId);
        ticket.setExternalKey("OPS-42");
        ticket.setStatus("To Do");
        ticket.setCreatedAt(now);
        ticket.setUpdatedAt(now);
        return ticket;
    }
}
