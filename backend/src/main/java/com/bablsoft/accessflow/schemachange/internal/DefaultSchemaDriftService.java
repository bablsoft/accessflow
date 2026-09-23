package com.bablsoft.accessflow.schemachange.internal;

import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.PageResponse;
import com.bablsoft.accessflow.schemachange.api.SchemaDriftConcurrentUpdateException;
import com.bablsoft.accessflow.schemachange.api.SchemaDriftFindingListFilter;
import com.bablsoft.accessflow.schemachange.api.SchemaDriftFindingNotAcknowledgeableException;
import com.bablsoft.accessflow.schemachange.api.SchemaDriftFindingNotFoundException;
import com.bablsoft.accessflow.schemachange.api.SchemaDriftFindingStatus;
import com.bablsoft.accessflow.schemachange.api.SchemaDriftFindingView;
import com.bablsoft.accessflow.schemachange.api.SchemaDriftScanInProgressException;
import com.bablsoft.accessflow.schemachange.api.SchemaDriftScanListFilter;
import com.bablsoft.accessflow.schemachange.api.SchemaDriftScanView;
import com.bablsoft.accessflow.schemachange.api.SchemaDriftService;
import com.bablsoft.accessflow.schemachange.internal.config.SchemaChangeProperties;
import com.bablsoft.accessflow.schemachange.internal.persistence.repo.SchemaDriftFindingRepository;
import com.bablsoft.accessflow.schemachange.internal.persistence.repo.SchemaDriftScanRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.HashMap;
import java.util.UUID;

/** Read side of schema drift plus the acknowledge and scan-now actions (#881). */
@Service
@RequiredArgsConstructor
public class DefaultSchemaDriftService implements SchemaDriftService {

    private final SchemaDriftScanRepository scanRepository;
    private final SchemaDriftFindingRepository findingRepository;
    private final SchemaDriftScanContextResolver contextResolver;
    private final SchemaDriftScanStore scanStore;
    private final SchemaDriftScanService scanService;
    private final SchemaChangeAuditWriter auditWriter;
    private final SchemaChangeProperties properties;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public PageResponse<SchemaDriftScanView> listScans(UUID organizationId, SchemaDriftScanListFilter filter,
                                                        PageRequest pageRequest) {
        return SchemaDriftPageAdapter.toPageResponse(
                scanRepository.findAll(SchemaDriftSpecifications.scans(organizationId, filter),
                        SchemaDriftPageAdapter.toSpringPageable(pageRequest))
                        .map(SchemaDriftViewMapper::toView));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<SchemaDriftFindingView> listFindings(UUID organizationId,
                                                              SchemaDriftFindingListFilter filter,
                                                              PageRequest pageRequest) {
        return SchemaDriftPageAdapter.toPageResponse(
                findingRepository.findAll(SchemaDriftSpecifications.findings(organizationId, filter),
                        SchemaDriftPageAdapter.toSpringPageable(pageRequest))
                        .map(SchemaDriftViewMapper::toView));
    }

    /**
     * Deliberately not {@code @Transactional}: the scan row must be committed before the cluster lock
     * is taken, because {@code runLockedAsync} hands the scan to an executor whose own transaction
     * would not see a row still uncommitted here.
     */
    @Override
    public SchemaDriftScanView scanNow(UUID organizationId, UUID actorId, UUID environmentId) {
        // 404 before 409: an environment the caller cannot see must not be distinguishable from a
        // busy one.
        var ctx = contextResolver.resolve(organizationId, environmentId);
        if (isScanInFlight(organizationId, environmentId)) {
            throw new SchemaDriftScanInProgressException(environmentId);
        }
        var scan = scanStore.open(ctx);
        boolean started;
        try {
            started = scanService.scanAsync(scan.getId(), ctx, actorId);
        } catch (RuntimeException ex) {
            // The handoff itself failed (the lock provider is down, the executor is shutting down).
            // Finish the row now, or the in-flight check would answer 409 until the lock ceiling.
            scanStore.abandon(scan.getId(), SchemaDriftScanReason.SCAN_FAILED + ": handoff failed");
            throw ex;
        }
        if (!started) {
            // Lost the cluster race between the pre-check and the lock. The row is finished with a
            // reason rather than left in flight, so it is auditable instead of invisible.
            scanStore.abandon(scan.getId(), SchemaDriftScanReason.SCAN_SUPERSEDED);
            throw new SchemaDriftScanInProgressException(environmentId);
        }
        return SchemaDriftViewMapper.toView(scan);
    }

    /**
     * Bounded by the lock's own ceiling. An unbounded "is anything unfinished?" check would outlive
     * the lock it shadows, so a scan row orphaned by a replica that died mid-run would make the
     * environment answer 409 forever.
     */
    private boolean isScanInFlight(UUID organizationId, UUID environmentId) {
        var horizon = clock.instant().minus(properties.driftScanLockAtMostFor());
        return scanRepository.existsByOrganizationIdAndEnvironmentIdAndFinishedAtIsNullAndStartedAtAfter(
                organizationId, environmentId, horizon);
    }

    @Override
    @Transactional
    public SchemaDriftFindingView acknowledge(UUID organizationId, UUID actorId, UUID findingId) {
        var finding = findingRepository.findByIdAndOrganizationId(findingId, organizationId)
                .orElseThrow(() -> new SchemaDriftFindingNotFoundException(findingId));
        if (finding.getStatus() == SchemaDriftFindingStatus.RESOLVED) {
            throw new SchemaDriftFindingNotAcknowledgeableException(findingId, finding.getStatus());
        }
        var previousStatus = finding.getStatus();
        if (previousStatus == SchemaDriftFindingStatus.OPEN) {
            finding.setStatus(SchemaDriftFindingStatus.ACKNOWLEDGED);
            try {
                // Flushed here so a scan that re-observed the finding meanwhile is reported as a
                // retryable 409 rather than surfacing at commit as a 500.
                findingRepository.saveAndFlush(finding);
            } catch (OptimisticLockingFailureException ex) {
                throw new SchemaDriftConcurrentUpdateException(findingId);
            }
        }
        recordAcknowledgeAudit(organizationId, actorId, finding.getId(), finding.getEnvironmentId(),
                finding.getObjectPath(), finding.getFindingKind().name(), previousStatus.name());
        return SchemaDriftViewMapper.toView(finding);
    }

    private void recordAcknowledgeAudit(UUID organizationId, UUID actorId, UUID findingId, UUID environmentId,
                                        String objectPath, String findingKind, String previousStatus) {
        var metadata = new HashMap<String, Object>();
        metadata.put("environment_id", environmentId.toString());
        metadata.put("object_path", objectPath);
        metadata.put("finding_kind", findingKind);
        metadata.put("previous_status", previousStatus);
        auditWriter.record(AuditAction.SCHEMA_DRIFT_FINDING_ACKNOWLEDGED,
                AuditResourceType.SCHEMA_DRIFT_FINDING, findingId, organizationId, actorId, metadata,
                null, null);
    }
}
