package com.bablsoft.accessflow.audit.internal;

import com.bablsoft.accessflow.audit.api.AuditSinkNameConflictException;
import com.bablsoft.accessflow.audit.api.AuditSinkNotFoundException;
import com.bablsoft.accessflow.audit.api.AuditSinkTestFailedException;
import com.bablsoft.accessflow.audit.api.AuditSinkType;
import com.bablsoft.accessflow.audit.api.CreateAuditSinkCommand;
import com.bablsoft.accessflow.audit.api.UpdateAuditSinkCommand;
import com.bablsoft.accessflow.audit.internal.codec.AuditSinkConfigCodec;
import com.bablsoft.accessflow.audit.internal.persistence.entity.AuditSinkEntity;
import com.bablsoft.accessflow.audit.internal.persistence.repo.AuditLogRepository;
import com.bablsoft.accessflow.audit.internal.persistence.repo.AuditSinkRepository;
import com.bablsoft.accessflow.audit.internal.sink.AuditExportEvent;
import com.bablsoft.accessflow.audit.internal.sink.AuditSinkDeliverer;
import com.bablsoft.accessflow.audit.internal.sink.AuditSinkDeliveryException;
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
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultAuditSinkServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-19T12:00:00Z");
    private static final UUID ORG_ID = UUID.randomUUID();
    private static final Map<String, Object> MASKED = Map.of("url", "https://x", "secret", "********");

    @Mock AuditSinkRepository sinkRepository;
    @Mock AuditLogRepository auditLogRepository;
    @Mock AuditSinkConfigCodec codec;
    @Mock AuditSinkDeliverer deliverer;

    private DefaultAuditSinkService service;

    @BeforeEach
    void setUp() {
        lenient().when(deliverer.type()).thenReturn(AuditSinkType.HTTPS_BATCH);
        lenient().when(codec.decodeForApi(any())).thenReturn(MASKED);
        lenient().when(auditLogRepository.findIdsAfterKeyset(any(), any(), any(), any()))
                .thenReturn(List.of());
        service = new DefaultAuditSinkService(sinkRepository, auditLogRepository, codec,
                Clock.fixed(NOW, ZoneOffset.UTC), List.of(deliverer));
    }

    private AuditSinkEntity entity() {
        var entity = new AuditSinkEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(ORG_ID);
        entity.setName("sink");
        entity.setType(AuditSinkType.HTTPS_BATCH);
        entity.setConfigJson("{\"stored\":true}");
        return entity;
    }

    // ---------------------------------------------------------------- create

    @Test
    void createAssignsIdKeepsEpochCursorAndEncodesConfig() {
        when(sinkRepository.existsByOrganizationIdAndName(ORG_ID, "splunk")).thenReturn(false);
        when(codec.encodeForPersistence(eq(AuditSinkType.SPLUNK_HEC), any()))
                .thenReturn("{\"encoded\":true}");
        when(sinkRepository.save(any(AuditSinkEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        var view = service.create(new CreateAuditSinkCommand(
                ORG_ID, AuditSinkType.SPLUNK_HEC, "splunk", Map.of("url", "https://x")));

        var captor = ArgumentCaptor.forClass(AuditSinkEntity.class);
        verify(sinkRepository).save(captor.capture());
        var saved = captor.getValue();
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getOrganizationId()).isEqualTo(ORG_ID);
        assertThat(saved.getName()).isEqualTo("splunk");
        assertThat(saved.getType()).isEqualTo(AuditSinkType.SPLUNK_HEC);
        assertThat(saved.getConfigJson()).isEqualTo("{\"encoded\":true}");
        // A new sink backfills the full history: the cursor stays at the epoch floor.
        assertThat(saved.getCursorCreatedAt()).isEqualTo(Instant.EPOCH);
        assertThat(saved.getCursorId()).isEqualTo(AuditSinkEntity.CURSOR_ID_FLOOR);
        assertThat(saved.isEnabled()).isTrue();

        assertThat(view.id()).isEqualTo(saved.getId());
        assertThat(view.config()).isEqualTo(MASKED);
    }

    @Test
    void createRejectsDuplicateName() {
        when(sinkRepository.existsByOrganizationIdAndName(ORG_ID, "dup")).thenReturn(true);

        assertThatThrownBy(() -> service.create(new CreateAuditSinkCommand(
                ORG_ID, AuditSinkType.HTTPS_BATCH, "dup", Map.of())))
                .isInstanceOf(AuditSinkNameConflictException.class);

        verify(sinkRepository, never()).save(any());
    }

    // ---------------------------------------------------------------- update

    @Test
    void updateAppliesNameConfigAndEnabled() {
        var entity = entity();
        when(sinkRepository.findByIdAndOrganizationId(entity.getId(), ORG_ID))
                .thenReturn(Optional.of(entity));
        when(sinkRepository.existsByOrganizationIdAndName(ORG_ID, "renamed")).thenReturn(false);
        when(codec.mergeForPersistence(eq(AuditSinkType.HTTPS_BATCH), eq("{\"stored\":true}"),
                any())).thenReturn("{\"merged\":true}");
        when(sinkRepository.save(entity)).thenReturn(entity);

        var view = service.update(entity.getId(), ORG_ID,
                new UpdateAuditSinkCommand("renamed", Map.of("secret", "new"), false));

        assertThat(entity.getName()).isEqualTo("renamed");
        assertThat(entity.getConfigJson()).isEqualTo("{\"merged\":true}");
        assertThat(entity.isEnabled()).isFalse();
        assertThat(view.name()).isEqualTo("renamed");
    }

    @Test
    void updateWithAllNullFieldsChangesNothing() {
        var entity = entity();
        when(sinkRepository.findByIdAndOrganizationId(entity.getId(), ORG_ID))
                .thenReturn(Optional.of(entity));
        when(sinkRepository.save(entity)).thenReturn(entity);

        service.update(entity.getId(), ORG_ID, new UpdateAuditSinkCommand(null, null, null));

        assertThat(entity.getName()).isEqualTo("sink");
        assertThat(entity.getConfigJson()).isEqualTo("{\"stored\":true}");
        assertThat(entity.isEnabled()).isTrue();
        verify(sinkRepository, never()).existsByOrganizationIdAndName(any(), any());
        verify(codec, never()).mergeForPersistence(any(), any(), any());
    }

    @Test
    void updateSkipsConflictCheckWhenNameUnchanged() {
        var entity = entity();
        when(sinkRepository.findByIdAndOrganizationId(entity.getId(), ORG_ID))
                .thenReturn(Optional.of(entity));
        when(sinkRepository.save(entity)).thenReturn(entity);

        service.update(entity.getId(), ORG_ID, new UpdateAuditSinkCommand("sink", null, null));

        verify(sinkRepository, never()).existsByOrganizationIdAndName(any(), any());
    }

    @Test
    void updateRejectsRenameToExistingName() {
        var entity = entity();
        when(sinkRepository.findByIdAndOrganizationId(entity.getId(), ORG_ID))
                .thenReturn(Optional.of(entity));
        when(sinkRepository.existsByOrganizationIdAndName(ORG_ID, "taken")).thenReturn(true);

        assertThatThrownBy(() -> service.update(entity.getId(), ORG_ID,
                new UpdateAuditSinkCommand("taken", null, null)))
                .isInstanceOf(AuditSinkNameConflictException.class);

        verify(sinkRepository, never()).save(any());
    }

    @Test
    void updateEmptyConfigMapSkipsMerge() {
        var entity = entity();
        when(sinkRepository.findByIdAndOrganizationId(entity.getId(), ORG_ID))
                .thenReturn(Optional.of(entity));
        when(sinkRepository.save(entity)).thenReturn(entity);

        service.update(entity.getId(), ORG_ID, new UpdateAuditSinkCommand(null, Map.of(), true));

        verify(codec, never()).mergeForPersistence(any(), any(), any());
    }

    @Test
    void updateUnknownSinkThrowsNotFound() {
        var id = UUID.randomUUID();
        when(sinkRepository.findByIdAndOrganizationId(id, ORG_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(id, ORG_ID,
                new UpdateAuditSinkCommand("x", null, null)))
                .isInstanceOf(AuditSinkNotFoundException.class);
    }

    // ---------------------------------------------------------------- delete

    @Test
    void deleteRemovesTheSink() {
        var entity = entity();
        when(sinkRepository.findByIdAndOrganizationId(entity.getId(), ORG_ID))
                .thenReturn(Optional.of(entity));

        service.delete(entity.getId(), ORG_ID);

        verify(sinkRepository).delete(entity);
    }

    @Test
    void deleteUnknownSinkThrowsNotFound() {
        var id = UUID.randomUUID();
        when(sinkRepository.findByIdAndOrganizationId(id, ORG_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(id, ORG_ID))
                .isInstanceOf(AuditSinkNotFoundException.class);
    }

    // ---------------------------------------------------------------- sendTest

    @Test
    void sendTestDeliversSyntheticEventThroughTheDeliverer() {
        var entity = entity();
        when(sinkRepository.findByIdAndOrganizationId(entity.getId(), ORG_ID))
                .thenReturn(Optional.of(entity));

        service.sendTest(entity.getId(), ORG_ID);

        var captor = ArgumentCaptor.forClass(AuditExportEvent.class);
        verify(deliverer).deliverTest(eq(entity), captor.capture());
        var event = captor.getValue();
        assertThat(event.action()).isEqualTo("AUDIT_SINK_TEST");
        assertThat(event.organizationId()).isEqualTo(ORG_ID);
        assertThat(event.resourceType()).isEqualTo("audit_sink");
        assertThat(event.createdAt()).isEqualTo(NOW);
        assertThat(event.id()).isNotNull();
        assertThat(event.metadataJson()).isEqualTo("{\"test\":true}");
    }

    @Test
    void sendTestWrapsDeliveryFailure() {
        var entity = entity();
        when(sinkRepository.findByIdAndOrganizationId(entity.getId(), ORG_ID))
                .thenReturn(Optional.of(entity));
        doThrow(new AuditSinkDeliveryException("unreachable"))
                .when(deliverer).deliverTest(any(), any());

        assertThatThrownBy(() -> service.sendTest(entity.getId(), ORG_ID))
                .isInstanceOf(AuditSinkTestFailedException.class)
                .hasMessageContaining("unreachable");
    }

    @Test
    void sendTestUnknownSinkThrowsNotFound() {
        var id = UUID.randomUUID();
        when(sinkRepository.findByIdAndOrganizationId(id, ORG_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.sendTest(id, ORG_ID))
                .isInstanceOf(AuditSinkNotFoundException.class);
    }

    @Test
    void sendTestWithoutRegisteredDelivererFailsLoudly() {
        var entity = entity();
        entity.setType(AuditSinkType.SPLUNK_HEC);
        when(sinkRepository.findByIdAndOrganizationId(entity.getId(), ORG_ID))
                .thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> service.sendTest(entity.getId(), ORG_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SPLUNK_HEC");
    }

    // ---------------------------------------------------------------- list / toView

    @Test
    void listMapsEntitiesToViewsWithMaskedConfig() {
        var entity = entity();
        entity.setLastError("boom");
        entity.setConsecutiveFailures(2);
        when(sinkRepository.findByOrganizationIdOrderByCreatedAtAsc(ORG_ID))
                .thenReturn(List.of(entity));
        when(auditLogRepository.findIdsAfterKeyset(eq(ORG_ID), any(), any(), any()))
                .thenReturn(List.of(UUID.randomUUID(), UUID.randomUUID()));

        var views = service.list(ORG_ID);

        assertThat(views).hasSize(1);
        var view = views.get(0);
        assertThat(view.id()).isEqualTo(entity.getId());
        assertThat(view.config()).isEqualTo(MASKED);
        assertThat(view.lastError()).isEqualTo("boom");
        assertThat(view.consecutiveFailures()).isEqualTo(2);
        assertThat(view.behindCount()).isEqualTo(2);
        assertThat(view.behindCountCapped()).isFalse();
    }

    @Test
    void behindCountIsCappedAtOneThousand() {
        var entity = entity();
        when(sinkRepository.findByOrganizationIdOrderByCreatedAtAsc(ORG_ID))
                .thenReturn(List.of(entity));
        when(auditLogRepository.findIdsAfterKeyset(eq(ORG_ID), any(), any(), any()))
                .thenReturn(Stream.generate(UUID::randomUUID).limit(1001).toList());

        var view = service.list(ORG_ID).get(0);

        assertThat(view.behindCount()).isEqualTo(1000);
        assertThat(view.behindCountCapped()).isTrue();
    }
}
