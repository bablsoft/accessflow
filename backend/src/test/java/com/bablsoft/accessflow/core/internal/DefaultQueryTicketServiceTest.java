package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.core.api.RecordTicketCommand;
import com.bablsoft.accessflow.core.internal.persistence.entity.QueryTicketEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.QueryTicketRepository;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultQueryTicketServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-17T12:00:00Z");

    @Mock QueryTicketRepository repository;

    private DefaultQueryTicketService service;

    private final UUID orgId = UUID.randomUUID();
    private final UUID queryRequestId = UUID.randomUUID();
    private final UUID channelId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new DefaultQueryTicketService(repository, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void recordCreatedPersistsAllFieldsAndStampsIdAndTimestamps() {
        when(repository.save(any(QueryTicketEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        var view = service.recordCreated(new RecordTicketCommand(orgId, queryRequestId, channelId,
                "SERVICENOW", "QUERY_REJECTED", "sys-id-1", "INC0010023",
                "https://sn.example/incident/1", "New"));

        var captor = ArgumentCaptor.forClass(QueryTicketEntity.class);
        verify(repository).save(captor.capture());
        var saved = captor.getValue();
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getOrganizationId()).isEqualTo(orgId);
        assertThat(saved.getQueryRequestId()).isEqualTo(queryRequestId);
        assertThat(saved.getChannelId()).isEqualTo(channelId);
        assertThat(saved.getTicketSystem()).isEqualTo("SERVICENOW");
        assertThat(saved.getTriggerEvent()).isEqualTo("QUERY_REJECTED");
        assertThat(saved.getExternalId()).isEqualTo("sys-id-1");
        assertThat(saved.getExternalKey()).isEqualTo("INC0010023");
        assertThat(saved.getUrl()).isEqualTo("https://sn.example/incident/1");
        assertThat(saved.getStatus()).isEqualTo("New");
        assertThat(saved.getCreatedAt()).isEqualTo(NOW);
        assertThat(saved.getUpdatedAt()).isEqualTo(NOW);

        assertThat(view.id()).isEqualTo(saved.getId());
        assertThat(view.organizationId()).isEqualTo(orgId);
        assertThat(view.queryRequestId()).isEqualTo(queryRequestId);
        assertThat(view.channelId()).isEqualTo(channelId);
        assertThat(view.ticketSystem()).isEqualTo("SERVICENOW");
        assertThat(view.triggerEvent()).isEqualTo("QUERY_REJECTED");
        assertThat(view.externalId()).isEqualTo("sys-id-1");
        assertThat(view.externalKey()).isEqualTo("INC0010023");
        assertThat(view.url()).isEqualTo("https://sn.example/incident/1");
        assertThat(view.status()).isEqualTo("New");
        assertThat(view.resolution()).isNull();
        assertThat(view.createdAt()).isEqualTo(NOW);
        assertThat(view.updatedAt()).isEqualTo(NOW);
    }

    @Test
    void existsForDelegatesToRepository() {
        when(repository.existsByChannelIdAndQueryRequestIdAndTriggerEvent(channelId,
                queryRequestId, "QUERY_REJECTED")).thenReturn(true);

        assertThat(service.existsFor(channelId, queryRequestId, "QUERY_REJECTED")).isTrue();
        verify(repository).existsByChannelIdAndQueryRequestIdAndTriggerEvent(channelId,
                queryRequestId, "QUERY_REJECTED");
    }

    @Test
    void updateStatusUpdatesEntityAndReturnsView() {
        var entity = entity();
        when(repository.findByChannelIdAndExternalId(channelId, "sys-id-1"))
                .thenReturn(Optional.of(entity));
        when(repository.save(any(QueryTicketEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        var view = service.updateStatus(channelId, "sys-id-1", "Resolved", "Fixed");

        assertThat(view).isPresent();
        assertThat(view.get().status()).isEqualTo("Resolved");
        assertThat(view.get().resolution()).isEqualTo("Fixed");
        assertThat(view.get().updatedAt()).isEqualTo(NOW);
        assertThat(entity.getStatus()).isEqualTo("Resolved");
        assertThat(entity.getResolution()).isEqualTo("Fixed");
        assertThat(entity.getUpdatedAt()).isEqualTo(NOW);
        verify(repository).save(entity);
    }

    @Test
    void updateStatusReturnsEmptyWhenTicketUnknown() {
        when(repository.findByChannelIdAndExternalId(channelId, "missing"))
                .thenReturn(Optional.empty());

        assertThat(service.updateStatus(channelId, "missing", "Resolved", null)).isEmpty();
        verify(repository).findByChannelIdAndExternalId(channelId, "missing");
    }

    @Test
    void listByQueryRequestMapsEntitiesToViews() {
        var entity = entity();
        when(repository.findAllByQueryRequestIdAndOrganizationIdOrderByCreatedAtAsc(
                queryRequestId, orgId)).thenReturn(List.of(entity));

        var views = service.listByQueryRequest(queryRequestId, orgId);

        assertThat(views).hasSize(1);
        var view = views.get(0);
        assertThat(view.id()).isEqualTo(entity.getId());
        assertThat(view.externalKey()).isEqualTo("INC0010023");
        assertThat(view.ticketSystem()).isEqualTo("SERVICENOW");
        assertThat(view.status()).isEqualTo("New");
    }

    @Test
    void listByQueryRequestReturnsEmptyWhenNoTickets() {
        when(repository.findAllByQueryRequestIdAndOrganizationIdOrderByCreatedAtAsc(
                queryRequestId, orgId)).thenReturn(List.of());

        assertThat(service.listByQueryRequest(queryRequestId, orgId)).isEmpty();
    }

    private QueryTicketEntity entity() {
        var e = new QueryTicketEntity();
        e.setId(UUID.randomUUID());
        e.setOrganizationId(orgId);
        e.setQueryRequestId(queryRequestId);
        e.setChannelId(channelId);
        e.setTicketSystem("SERVICENOW");
        e.setTriggerEvent("QUERY_REJECTED");
        e.setExternalId("sys-id-1");
        e.setExternalKey("INC0010023");
        e.setUrl("https://sn.example/incident/1");
        e.setStatus("New");
        e.setCreatedAt(NOW.minusSeconds(60));
        e.setUpdatedAt(NOW.minusSeconds(60));
        return e;
    }
}
