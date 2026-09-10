package com.bablsoft.accessflow.discovery.internal;

import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditEntry;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import com.bablsoft.accessflow.discovery.api.DiscoveryDetector;
import com.bablsoft.accessflow.discovery.api.DiscoveryFindingStatus;
import com.bablsoft.accessflow.discovery.internal.config.DiscoveryProperties;
import com.bablsoft.accessflow.discovery.internal.persistence.entity.DiscoveryFindingEntity;
import com.bablsoft.accessflow.discovery.internal.persistence.repo.DiscoveryFindingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Set;
import java.util.UUID;

/**
 * Ages {@code PENDING} findings a scan no longer proposes, and retires them as {@code STALE}
 * once they have been missed {@code accessflow.discovery.stale-scans-before-expiry} times in a
 * row (AF-659).
 *
 * <p><strong>Only findings whose table the run actually sampled are eligible.</strong> A run
 * truncated by the table cap or the time budget, a table whose sample threw, a table that
 * returned no usable result set, and a table dropped from the schema entirely all leave their
 * findings untouched — otherwise a partial run would retire the worklist it never looked at.
 * Findings from the {@code AI} detector are additionally gated on the AI pass having run for that
 * table, because the pass is capped far below the table cap and is opt-in: without that gate,
 * turning the AI pass off would retire every AI finding in the estate.
 *
 * <p>Deliberately not transactional, and each row is saved independently — the same stance as
 * {@code DefaultDiscoveryFindingService.decide}. A row an admin decided concurrently loses the
 * optimistic-lock check; the admin's decision wins and the sweep simply skips it. Nothing here
 * may throw: the caller has already completed a real scan, and a bookkeeping failure must never
 * mark that scan failed.
 *
 * <p>Miss counting assumes one scan of a datasource at a time, which the cluster-wide
 * {@code discoveryScan:<datasourceId>} lock guarantees (AF-660). Even a double-count would only
 * retire a finding a cycle early: {@code STALE} is reversible — re-detection revives the finding to
 * {@code PENDING} with the counter reset.
 */
@Service
@RequiredArgsConstructor
@Slf4j
class DiscoveryStaleSweepService {

    /**
     * Ceiling on per-finding expiry audit rows written by one scan. The audit log is hash-chained,
     * so every row costs a read-then-insert round trip; the bulk-decision endpoint caps its own
     * per-finding fan-out at the same 100. Beyond the cap the counters still report the true
     * totals and {@code expiredAuditTruncated} is set on the scan audit.
     */
    private static final int MAX_EXPIRY_AUDIT_ROWS = 100;

    private final DiscoveryFindingRepository findingRepository;
    private final AuditLogService auditLogService;
    private final DiscoveryProperties properties;

    /** Outcome of one sweep. {@code aged} counts every miss recorded, including those that expired. */
    record SweepResult(int aged, int expired, boolean expiredAuditTruncated) {

        static final SweepResult NONE = new SweepResult(0, 0, false);
    }

    SweepResult sweep(UUID datasourceId, UUID organizationId, Set<DiscoveryTableKey> scannedTables,
                      Set<DiscoveryTableKey> aiScannedTables, Set<UUID> seenFindingIds) {
        if (scannedTables.isEmpty()) {
            return SweepResult.NONE;
        }
        var threshold = properties.staleScansBeforeExpiry();
        var aged = 0;
        var expired = 0;
        var audited = 0;
        for (var finding : findingRepository.findAllByDatasourceIdAndOrganizationIdAndStatus(
                datasourceId, organizationId, DiscoveryFindingStatus.PENDING)) {
            if (seenFindingIds.contains(finding.getId())
                    || !isEligible(finding, scannedTables, aiScannedTables)) {
                continue;
            }
            var missed = finding.getMissedScanCount() + 1;
            finding.setMissedScanCount(missed);
            var expiring = missed >= threshold;
            if (expiring) {
                finding.setStatus(DiscoveryFindingStatus.STALE);
            }
            if (!save(finding)) {
                continue;
            }
            aged++;
            if (expiring) {
                expired++;
                if (audited < MAX_EXPIRY_AUDIT_ROWS) {
                    recordExpiryAudit(datasourceId, organizationId, finding);
                    audited++;
                }
            }
        }
        if (aged > 0) {
            log.info("Discovery sweep for datasource {} aged {} findings ({} retired as stale)",
                    datasourceId, aged, expired);
        }
        return new SweepResult(aged, expired, expired > audited);
    }

    /**
     * A finding may only age when this run sampled its table — and, for an AI proposal, when the
     * AI pass actually ran for that table.
     */
    private static boolean isEligible(DiscoveryFindingEntity finding,
                                      Set<DiscoveryTableKey> scannedTables,
                                      Set<DiscoveryTableKey> aiScannedTables) {
        var key = DiscoveryTableKey.of(finding.getSchemaName(), finding.getTableName());
        if (!scannedTables.contains(key)) {
            return false;
        }
        return finding.getDetector() != DiscoveryDetector.AI || aiScannedTables.contains(key);
    }

    /** @return true when the row was persisted; false when it lost a race and was skipped. */
    private boolean save(DiscoveryFindingEntity finding) {
        try {
            findingRepository.save(finding);
            return true;
        } catch (RuntimeException ex) {
            // Almost always an admin deciding this finding concurrently: their decision wins, and
            // the next scan re-counts the miss. One contended row must not abort the sweep.
            log.debug("Skipping stale sweep of discovery finding {}: {}", finding.getId(),
                    ex.getMessage());
            return false;
        }
    }

    private void recordExpiryAudit(UUID datasourceId, UUID organizationId,
                                   DiscoveryFindingEntity finding) {
        try {
            var metadata = new HashMap<String, Object>();
            metadata.put("datasourceId", datasourceId.toString());
            metadata.put("tableName", qualifiedTable(finding));
            metadata.put("columnName", finding.getColumnName());
            metadata.put("classification", finding.getClassification().name());
            metadata.put("detector", finding.getDetector().name());
            metadata.put("missedScanCount", finding.getMissedScanCount());
            auditLogService.record(new AuditEntry(AuditAction.DISCOVERY_FINDING_EXPIRED,
                    AuditResourceType.DISCOVERY_FINDING, finding.getId(), organizationId, null,
                    metadata, null, null));
        } catch (RuntimeException ex) {
            log.error("Audit write failed for expired discovery finding {}", finding.getId(), ex);
        }
    }

    private static String qualifiedTable(DiscoveryFindingEntity finding) {
        return finding.getSchemaName() == null ? finding.getTableName()
                : finding.getSchemaName() + "." + finding.getTableName();
    }
}
