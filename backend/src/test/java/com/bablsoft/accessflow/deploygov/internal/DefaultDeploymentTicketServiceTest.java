package com.bablsoft.accessflow.deploygov.internal;

import com.bablsoft.accessflow.deploygov.api.RecordDeploymentTicketCommand;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentTicketEntity;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentTicketRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultDeploymentTicketServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-21T12:00:00Z");
    private static final Instant EARLIER = Instant.parse("2026-10-20T08:00:00Z");

    @Mock DeploymentTicketRepository repository;

    private DefaultDeploymentTicketService service;

    private final UUID orgId = UUID.randomUUID();
    private final UUID requestId = UUID.randomUUID();
    private final UUID channelId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new DefaultDeploymentTicketService(repository, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void recordCreatedPersistsAllFieldsUndecided() {
        when(repository.save(any(DeploymentTicketEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        var view = service.recordCreated(new RecordDeploymentTicketCommand(orgId, requestId,
                channelId, "SERVICENOW", "DEPLOYMENT_SUBMITTED", "sys-id-1", "CHG0030012",
                "https://sn.example/chg/1", "New"));

        var captor = ArgumentCaptor.forClass(DeploymentTicketEntity.class);
        verify(repository).save(captor.capture());
        var saved = captor.getValue();
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getOrganizationId()).isEqualTo(orgId);
        assertThat(saved.getDeploymentRequestId()).isEqualTo(requestId);
        assertThat(saved.getChannelId()).isEqualTo(channelId);
        assertThat(saved.getTicketSystem()).isEqualTo("SERVICENOW");
        assertThat(saved.getTriggerEvent()).isEqualTo("DEPLOYMENT_SUBMITTED");
        assertThat(saved.getExternalId()).isEqualTo("sys-id-1");
        assertThat(saved.getExternalKey()).isEqualTo("CHG0030012");
        assertThat(saved.getUrl()).isEqualTo("https://sn.example/chg/1");
        assertThat(saved.getStatus()).isEqualTo("New");
        assertThat(saved.getCreatedAt()).isEqualTo(NOW);
        assertThat(saved.getUpdatedAt()).isEqualTo(NOW);
        assertThat(view.id()).isEqualTo(saved.getId());
        assertThat(view.deploymentRequestId()).isEqualTo(requestId);
        assertThat(view.decision()).isNull();
        assertThat(view.decidedAt()).isNull();
        assertThat(view.resolution()).isNull();
    }

    @Test
    void existsForDelegatesToTheChannelRequestDedupeKey() {
        when(repository.existsByChannelIdAndDeploymentRequestId(channelId, requestId))
                .thenReturn(true);

        assertThat(service.existsFor(channelId, requestId)).isTrue();
    }

    @Test
    void updateStatusOnUnknownTicketIsEmptyAndWritesNothing() {
        when(repository.findByChannelIdAndExternalId(channelId, "missing"))
                .thenReturn(Optional.empty());

        assertThat(service.updateStatus(channelId, "missing", "Closed", null, "APPROVED")).isEmpty();
        verify(repository, never()).save(any());
    }

    @Test
    void updateStatusStampsAFreshDecision() {
        var entity = ticket(null, null);
        when(repository.findByChannelIdAndExternalId(channelId, "sys-id-1"))
                .thenReturn(Optional.of(entity));
        when(repository.save(entity)).thenReturn(entity);

        var view = service.updateStatus(channelId, "sys-id-1", "Implement", "Approved",
                "APPROVED").orElseThrow();

        assertThat(view.status()).isEqualTo("Implement");
        assertThat(view.resolution()).isEqualTo("Approved");
        assertThat(view.decision()).isEqualTo("APPROVED");
        assertThat(view.decidedAt()).isEqualTo(NOW);
        assertThat(view.updatedAt()).isEqualTo(NOW);
    }

    @Test
    void updateStatusKeepsDecidedAtWhenTheSameDecisionIsResent() {
        var entity = ticket("APPROVED", EARLIER);
        when(repository.findByChannelIdAndExternalId(channelId, "sys-id-1"))
                .thenReturn(Optional.of(entity));
        when(repository.save(entity)).thenReturn(entity);

        var view = service.updateStatus(channelId, "sys-id-1", "Closed", "Done", "APPROVED")
                .orElseThrow();

        assertThat(view.decision()).isEqualTo("APPROVED");
        assertThat(view.decidedAt()).isEqualTo(EARLIER);
        assertThat(view.updatedAt()).isEqualTo(NOW);
    }

    @Test
    void updateStatusRestampsAChangedDecision() {
        var entity = ticket("APPROVED", EARLIER);
        when(repository.findByChannelIdAndExternalId(channelId, "sys-id-1"))
                .thenReturn(Optional.of(entity));
        when(repository.save(entity)).thenReturn(entity);

        var view = service.updateStatus(channelId, "sys-id-1", "Cancelled", null, "REJECTED")
                .orElseThrow();

        assertThat(view.decision()).isEqualTo("REJECTED");
        assertThat(view.decidedAt()).isEqualTo(NOW);
    }

    @Test
    void updateStatusWithNullDecisionKeepsAnApproval() {
        var entity = ticket("APPROVED", EARLIER);
        when(repository.findByChannelIdAndExternalId(channelId, "sys-id-1"))
                .thenReturn(Optional.of(entity));
        when(repository.save(entity)).thenReturn(entity);

        var view = service.updateStatus(channelId, "sys-id-1", "Implement", null, null)
                .orElseThrow();

        assertThat(view.status()).isEqualTo("Implement");
        assertThat(view.resolution()).isNull();
        assertThat(view.decision()).isEqualTo("APPROVED");
        assertThat(view.decidedAt()).isEqualTo(EARLIER);
        assertThat(view.updatedAt()).isEqualTo(NOW);
    }

    @Test
    void updateStatusWithNullDecisionClearsARejection() {
        var entity = ticket("REJECTED", EARLIER);
        when(repository.findByChannelIdAndExternalId(channelId, "sys-id-1"))
                .thenReturn(Optional.of(entity));
        when(repository.save(entity)).thenReturn(entity);

        var view = service.updateStatus(channelId, "sys-id-1", "In Progress", null, null)
                .orElseThrow();

        assertThat(view.status()).isEqualTo("In Progress");
        assertThat(view.decision()).isNull();
        assertThat(view.decidedAt()).isNull();
    }

    @Test
    void listByDeploymentRequestIsOrganizationScoped() {
        var first = ticket(null, null);
        var second = ticket("REJECTED", EARLIER);
        when(repository.findAllByDeploymentRequestIdAndOrganizationIdOrderByCreatedAtAsc(requestId,
                orgId)).thenReturn(List.of(first, second));

        var views = service.listByDeploymentRequest(requestId, orgId);

        assertThat(views).extracting(v -> v.id()).containsExactly(first.getId(), second.getId());
        assertThat(views.get(1).decision()).isEqualTo("REJECTED");
    }

    private DeploymentTicketEntity ticket(String decision, Instant decidedAt) {
        var entity = new DeploymentTicketEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(orgId);
        entity.setDeploymentRequestId(requestId);
        entity.setChannelId(channelId);
        entity.setTicketSystem("SERVICENOW");
        entity.setTriggerEvent("DEPLOYMENT_SUBMITTED");
        entity.setExternalId("sys-id-1");
        entity.setExternalKey("CHG0030012");
        entity.setStatus("New");
        entity.setDecision(decision);
        entity.setDecidedAt(decidedAt);
        entity.setCreatedAt(EARLIER);
        entity.setUpdatedAt(EARLIER);
        return entity;
    }
}
