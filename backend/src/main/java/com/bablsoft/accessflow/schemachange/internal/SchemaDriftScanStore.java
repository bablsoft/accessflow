package com.bablsoft.accessflow.schemachange.internal;

import com.bablsoft.accessflow.schemachange.internal.persistence.entity.SchemaDriftScanEntity;
import com.bablsoft.accessflow.schemachange.internal.persistence.repo.SchemaDriftConfigRepository;
import com.bablsoft.accessflow.schemachange.internal.persistence.repo.SchemaDriftFindingRepository;
import com.bablsoft.accessflow.schemachange.internal.persistence.repo.SchemaDriftScanRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

/**
 * The drift scan's own transaction boundaries (#881), kept out of {@code SchemaDriftScanService} so
 * they cannot be lost to self-invocation: a {@code @Transactional} method a bean calls on itself
 * bypasses the proxy and silently runs unannotated.
 *
 * <p>Every method is {@code REQUIRES_NEW}. For {@link #open} that is load bearing rather than
 * tidiness: the on-demand path hands the scan to an executor as soon as the cluster lock is
 * acquired, and the executor's transaction would not see a row still uncommitted in the caller's.
 */
@Component
@RequiredArgsConstructor
class SchemaDriftScanStore {

    private final SchemaDriftScanRepository scanRepository;
    private final SchemaDriftConfigRepository configRepository;
    private final SchemaDriftFindingRepository findingRepository;
    private final Clock clock;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public SchemaDriftScanEntity open(SchemaDriftScanContext ctx) {
        var scan = new SchemaDriftScanEntity();
        scan.setId(UUID.randomUUID());
        scan.setOrganizationId(ctx.organizationId());
        scan.setPipelineId(ctx.pipelineId());
        scan.setEnvironmentId(ctx.environmentId());
        scan.setDatasourceId(ctx.datasourceId());
        scan.setBaseline(ctx.baseline());
        scan.setStartedAt(clock.instant());
        return scanRepository.saveAndFlush(scan);
    }

    /**
     * Always called, on every path, so a completed run never leaves a scan row in flight.
     * {@code findings_count} is counted from the rows the scan owns at completion rather than tallied
     * during the run, so even a reconciliation that failed halfway records the number its drill-down
     * showed at that moment.
     *
     * @return the recorded {@code findings_count}, or 0 when the row has vanished
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int finish(UUID scanId, boolean applicable, boolean partial, String reason) {
        var scan = scanRepository.findById(scanId).orElse(null);
        if (scan == null) {
            return 0;
        }
        var findingsCount = Math.toIntExact(findingRepository.countByScan_Id(scanId));
        scan.setFinishedAt(clock.instant());
        scan.setApplicable(applicable);
        scan.setPartial(partial);
        scan.setFindingsCount(findingsCount);
        scan.setErrorMessage(reason);
        scanRepository.saveAndFlush(scan);
        return findingsCount;
    }

    /** Finishes a scan that never ran, so a lost race leaves an explained row rather than none. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void abandon(UUID scanId, String reasonCode) {
        scanRepository.findById(scanId).ifPresent(scan -> {
            scan.setFinishedAt(clock.instant());
            scan.setErrorMessage(reasonCode);
            scanRepository.saveAndFlush(scan);
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void stampConfig(UUID pipelineId, UUID organizationId, String reason) {
        configRepository.findByPipelineIdAndOrganizationId(pipelineId, organizationId)
                .ifPresent(config -> {
                    config.setLastScanAt(clock.instant());
                    config.setLastScanError(reason);
                    configRepository.saveAndFlush(config);
                });
    }
}
