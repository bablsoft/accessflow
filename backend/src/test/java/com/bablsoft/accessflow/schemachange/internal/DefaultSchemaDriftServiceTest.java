package com.bablsoft.accessflow.schemachange.internal;

import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.schemachange.api.SchemaChangeEnvironmentNotFoundException;
import com.bablsoft.accessflow.schemachange.api.SchemaDriftBaseline;
import com.bablsoft.accessflow.schemachange.api.SchemaDriftConcurrentUpdateException;
import com.bablsoft.accessflow.schemachange.api.SchemaDriftFindingKind;
import com.bablsoft.accessflow.schemachange.api.SchemaDriftFindingListFilter;
import com.bablsoft.accessflow.schemachange.api.SchemaDriftFindingNotAcknowledgeableException;
import com.bablsoft.accessflow.schemachange.api.SchemaDriftFindingNotFoundException;
import com.bablsoft.accessflow.schemachange.api.SchemaDriftFindingStatus;
import com.bablsoft.accessflow.schemachange.api.SchemaDriftScanInProgressException;
import com.bablsoft.accessflow.schemachange.api.SchemaDriftScanListFilter;
import com.bablsoft.accessflow.schemachange.internal.config.SchemaChangeProperties;
import com.bablsoft.accessflow.schemachange.internal.persistence.entity.SchemaDriftFindingEntity;
import com.bablsoft.accessflow.schemachange.internal.persistence.entity.SchemaDriftScanEntity;
import com.bablsoft.accessflow.schemachange.internal.persistence.repo.SchemaDriftFindingRepository;
import com.bablsoft.accessflow.schemachange.internal.persistence.repo.SchemaDriftScanRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.jpa.domain.Specification;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultSchemaDriftServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-22T10:00:00Z");

    @Mock SchemaDriftScanRepository scanRepository;
    @Mock SchemaDriftFindingRepository findingRepository;
    @Mock SchemaDriftScanContextResolver contextResolver;
    @Mock SchemaDriftScanStore scanStore;
    @Mock SchemaDriftScanService scanService;
    @Mock SchemaChangeAuditWriter auditWriter;

    private DefaultSchemaDriftService service;

    private final UUID orgId = UUID.randomUUID();
    private final UUID actorId = UUID.randomUUID();
    private final UUID environmentId = UUID.randomUUID();
    private final UUID pipelineId = UUID.randomUUID();
    private final UUID datasourceId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new DefaultSchemaDriftService(scanRepository, findingRepository, contextResolver,
                scanStore, scanService, auditWriter,
                new SchemaChangeProperties(null, null, null, null, null, null),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private SchemaDriftScanContext ctx() {
        return new SchemaDriftScanContext(orgId, pipelineId, environmentId, datasourceId,
                DbType.POSTGRESQL, SchemaDriftBaseline.PREVIOUS_ENVIRONMENT, null);
    }

    private SchemaDriftScanEntity scan() {
        var scan = new SchemaDriftScanEntity();
        scan.setId(UUID.randomUUID());
        scan.setOrganizationId(orgId);
        scan.setPipelineId(pipelineId);
        scan.setEnvironmentId(environmentId);
        scan.setDatasourceId(datasourceId);
        scan.setBaseline(SchemaDriftBaseline.PREVIOUS_ENVIRONMENT);
        return scan;
    }

    private SchemaDriftFindingEntity finding(SchemaDriftFindingStatus status) {
        var entity = new SchemaDriftFindingEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(orgId);
        entity.setEnvironmentId(environmentId);
        entity.setObjectPath("public.orders.email");
        entity.setFindingKind(SchemaDriftFindingKind.TYPE_MISMATCH);
        entity.setStatus(status);
        entity.setScan(scan());
        return entity;
    }

    // --- listings --------------------------------------------------------------------------------

    @Test
    void listsScansThroughTheSpecification() {
        when(scanRepository.findAll(any(Specification.class), any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(scan())));

        var page = service.listScans(orgId, new SchemaDriftScanListFilter(pipelineId, environmentId),
                PageRequest.of(0, 20));

        assertThat(page.content()).hasSize(1);
        assertThat(page.content().getFirst().environmentId()).isEqualTo(environmentId);
    }

    @Test
    void listsFindingsThroughTheSpecification() {
        when(findingRepository.findAll(any(Specification.class), any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(finding(SchemaDriftFindingStatus.OPEN))));

        var page = service.listFindings(orgId,
                new SchemaDriftFindingListFilter(pipelineId, environmentId, SchemaDriftFindingStatus.OPEN),
                PageRequest.of(0, 20));

        assertThat(page.content()).hasSize(1);
        assertThat(page.content().getFirst().objectPath()).isEqualTo("public.orders.email");
    }

    // --- scan now --------------------------------------------------------------------------------

    @Test
    void scanNowOpensTheRowAndHandsItToTheAsyncPath() {
        var scan = scan();
        when(contextResolver.resolve(orgId, environmentId)).thenReturn(ctx());
        when(scanStore.open(any())).thenReturn(scan);
        when(scanService.scanAsync(eq(scan.getId()), any(), eq(actorId))).thenReturn(true);

        var view = service.scanNow(orgId, actorId, environmentId);

        assertThat(view.id()).isEqualTo(scan.getId());
    }

    @Test
    void anUnknownEnvironmentIsRejectedBeforeTheInFlightCheck() {
        when(contextResolver.resolve(orgId, environmentId))
                .thenThrow(new SchemaChangeEnvironmentNotFoundException(environmentId));

        // 404 before 409: a busy environment must not be distinguishable from one you cannot see.
        assertThatThrownBy(() -> service.scanNow(orgId, actorId, environmentId))
                .isInstanceOf(SchemaChangeEnvironmentNotFoundException.class);
        verify(scanRepository, never())
                .existsByOrganizationIdAndEnvironmentIdAndFinishedAtIsNullAndStartedAtAfter(any(), any(), any());
        verify(scanStore, never()).open(any());
    }

    @Test
    void aScanAlreadyInFlightIsRefusedWithoutOpeningARow() {
        when(contextResolver.resolve(orgId, environmentId)).thenReturn(ctx());
        when(scanRepository.existsByOrganizationIdAndEnvironmentIdAndFinishedAtIsNullAndStartedAtAfter(
                eq(orgId), eq(environmentId), any())).thenReturn(true);

        assertThatThrownBy(() -> service.scanNow(orgId, actorId, environmentId))
                .isInstanceOf(SchemaDriftScanInProgressException.class);
        verify(scanStore, never()).open(any());
        verify(scanService, never()).scanAsync(any(), any(), any());
    }

    @Test
    void theInFlightCheckIsBoundedByTheLockHorizon() {
        when(contextResolver.resolve(orgId, environmentId)).thenReturn(ctx());
        var scan = scan();
        when(scanStore.open(any())).thenReturn(scan);
        when(scanService.scanAsync(any(), any(), any())).thenReturn(true);

        service.scanNow(orgId, actorId, environmentId);

        // A row orphaned by a dead replica must stop blocking once its lock could no longer be held.
        verify(scanRepository).existsByOrganizationIdAndEnvironmentIdAndFinishedAtIsNullAndStartedAtAfter(
                orgId, environmentId, NOW.minus(java.time.Duration.ofMinutes(30)));
    }

    @Test
    void losingTheClusterRaceAbandonsTheRowAndStillReportsAConflict() {
        var scan = scan();
        when(contextResolver.resolve(orgId, environmentId)).thenReturn(ctx());
        when(scanStore.open(any())).thenReturn(scan);
        when(scanService.scanAsync(any(), any(), any())).thenReturn(false);

        assertThatThrownBy(() -> service.scanNow(orgId, actorId, environmentId))
                .isInstanceOf(SchemaDriftScanInProgressException.class);
        // Auditable rather than invisible: the row is finished with a reason, not left in flight.
        verify(scanStore).abandon(scan.getId(), SchemaDriftScanReason.SCAN_SUPERSEDED);
    }

    // --- acknowledge -----------------------------------------------------------------------------

    @Test
    void acknowledgingAnOpenFindingMovesItAndAudits() {
        var finding = finding(SchemaDriftFindingStatus.OPEN);
        when(findingRepository.findByIdAndOrganizationId(finding.getId(), orgId))
                .thenReturn(Optional.of(finding));

        var view = service.acknowledge(orgId, actorId, finding.getId());

        assertThat(view.status()).isEqualTo(SchemaDriftFindingStatus.ACKNOWLEDGED);
        verify(findingRepository).saveAndFlush(finding);
        verify(auditWriter).record(eq(AuditAction.SCHEMA_DRIFT_FINDING_ACKNOWLEDGED),
                eq(AuditResourceType.SCHEMA_DRIFT_FINDING), eq(finding.getId()), eq(orgId), eq(actorId),
                any(), eq(null), eq(null));
    }

    @Test
    void acknowledgingAnAlreadyAcknowledgedFindingIsIdempotent() {
        var finding = finding(SchemaDriftFindingStatus.ACKNOWLEDGED);
        when(findingRepository.findByIdAndOrganizationId(finding.getId(), orgId))
                .thenReturn(Optional.of(finding));

        assertThat(service.acknowledge(orgId, actorId, finding.getId()).status())
                .isEqualTo(SchemaDriftFindingStatus.ACKNOWLEDGED);
        verify(findingRepository, never()).saveAndFlush(any());
    }

    @Test
    void aResolvedFindingCanNoLongerBeAcknowledged() {
        var finding = finding(SchemaDriftFindingStatus.RESOLVED);
        when(findingRepository.findByIdAndOrganizationId(finding.getId(), orgId))
                .thenReturn(Optional.of(finding));

        assertThatThrownBy(() -> service.acknowledge(orgId, actorId, finding.getId()))
                .isInstanceOf(SchemaDriftFindingNotAcknowledgeableException.class);
    }

    @Test
    void aFindingInAnotherOrganizationIsNotFound() {
        var findingId = UUID.randomUUID();
        when(findingRepository.findByIdAndOrganizationId(findingId, orgId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.acknowledge(orgId, actorId, findingId))
                .isInstanceOf(SchemaDriftFindingNotFoundException.class);
    }

    @Test
    void anAcknowledgementRacingAScanIsARetryableConflict() {
        var finding = finding(SchemaDriftFindingStatus.OPEN);
        when(findingRepository.findByIdAndOrganizationId(finding.getId(), orgId)).thenReturn(Optional.of(finding));
        when(findingRepository.saveAndFlush(finding)).thenThrow(
                new org.springframework.orm.ObjectOptimisticLockingFailureException(
                        SchemaDriftFindingEntity.class, finding.getId()));

        assertThatThrownBy(() -> service.acknowledge(orgId, actorId, finding.getId()))
                .isInstanceOf(SchemaDriftConcurrentUpdateException.class);
        verify(auditWriter, never()).record(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void aFailedHandoffFinishesTheRowSoTheEnvironmentIsNotBlocked() {
        var scan = scan();
        when(contextResolver.resolve(orgId, environmentId)).thenReturn(ctx());
        when(scanStore.open(any())).thenReturn(scan);
        var redisDown = new IllegalStateException("redis down");
        when(scanService.scanAsync(any(), any(), any())).thenThrow(redisDown);

        // Without the abandon the in-flight check would answer 409 until the lock ceiling passed.
        assertThatThrownBy(() -> service.scanNow(orgId, actorId, environmentId)).isSameAs(redisDown);
        verify(scanStore).abandon(scan.getId(), SchemaDriftScanReason.SCAN_FAILED + ": handoff failed");
    }

    @Test
    @SuppressWarnings("unchecked")
    void theAcknowledgeAuditRowUsesSnakeCaseKeys() {
        var finding = finding(SchemaDriftFindingStatus.OPEN);
        when(findingRepository.findByIdAndOrganizationId(finding.getId(), orgId)).thenReturn(Optional.of(finding));

        service.acknowledge(orgId, actorId, finding.getId());

        var metadata = org.mockito.ArgumentCaptor.forClass(java.util.Map.class);
        verify(auditWriter).record(any(), any(), any(), any(), any(), metadata.capture(), any(), any());
        assertThat(metadata.getValue())
                .containsEntry("object_path", "public.orders.email")
                .containsEntry("finding_kind", "TYPE_MISMATCH")
                .containsEntry("previous_status", "OPEN")
                .containsKey("environment_id");
    }
}
