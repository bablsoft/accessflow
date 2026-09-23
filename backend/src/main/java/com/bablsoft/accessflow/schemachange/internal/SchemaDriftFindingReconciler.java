package com.bablsoft.accessflow.schemachange.internal;

import com.bablsoft.accessflow.schemachange.api.SchemaDriftFindingKind;
import com.bablsoft.accessflow.schemachange.api.SchemaDriftFindingStatus;
import com.bablsoft.accessflow.schemachange.internal.persistence.entity.SchemaDriftFindingEntity;
import com.bablsoft.accessflow.schemachange.internal.persistence.entity.SchemaDriftScanEntity;
import com.bablsoft.accessflow.schemachange.internal.persistence.repo.SchemaDriftFindingRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Carries drift findings across scans (#881): what is still drifted, what has just appeared, and
 * what has gone away.
 *
 * <p>The subtle part is what may be <em>resolved</em>. A scan only ever resolves a finding it could
 * have re-observed: one under a table it actually compared, of a kind it actually compared. A table
 * the table cap skipped or the time budget cut off keeps its findings untouched, and so do foreign-key
 * findings on a scan that suppressed foreign-key comparison — "we did not look" and "it is fixed" are
 * different facts. The same gate stops a scan whose schema comparison was itself cut short from
 * resolving anything at all. This mirrors the discovery module's scanned-tables coupling (AF-659).
 *
 * <p>Each row is saved on its own, under its {@code @Version}. A row an admin acknowledged while the
 * scan was running loses to the admin: the scan skips it and the next one re-evaluates it, rather than
 * writing a stale status back over the acknowledgement.
 */
@Component
@RequiredArgsConstructor
class SchemaDriftFindingReconciler {

    private static final Logger log = LoggerFactory.getLogger(SchemaDriftFindingReconciler.class);

    private static final Set<SchemaDriftFindingStatus> ACTIVE =
            Set.of(SchemaDriftFindingStatus.OPEN, SchemaDriftFindingStatus.ACKNOWLEDGED);

    private final SchemaDriftFindingRepository findingRepository;

    /**
     * Reconciles one scan's findings, calling {@code onOpened} once per finding it <em>opened</em>
     * (#882): a finding created now, a resolved one reopened as a new episode, or an acknowledged one
     * reopened because its values changed — a difference nobody accepted. A finding merely re-seen
     * while already open is never counted, so {@code SCHEMA_DRIFT_DETECTED} fires on new drift only.
     * A callback rather than a return value, so a reconcile that fails part-way still reports the
     * rows it had already committed.
     */
    void reconcile(SchemaDriftScanEntity scan, SchemaDriftScanContext ctx, SchemaDriftDiffer.DiffResult result,
                   Instant now, Runnable onOpened) {
        var active = findingRepository.findAllByOrganizationIdAndEnvironmentIdAndStatusIn(
                ctx.organizationId(), ctx.environmentId(), ACTIVE);
        Map<String, SchemaDriftFindingEntity> byKey = new HashMap<>();
        for (var finding : active) {
            byKey.put(naturalKey(finding.getObjectPath(), finding.getFindingKind()), finding);
        }

        Set<UUID> observed = new HashSet<>();
        for (var finding : result.findings()) {
            var existing = byKey.get(naturalKey(finding.objectPath(), finding.kind()));
            if (existing == null) {
                // Not in the active set: it may still exist as a resolved row from an earlier episode.
                existing = findingRepository
                        .findFirstByOrganizationIdAndEnvironmentIdAndObjectPathAndFindingKindOrderByLastSeenAtDesc(
                                ctx.organizationId(), ctx.environmentId(), finding.objectPath(), finding.kind())
                        .orElse(null);
            }
            if (existing == null) {
                findingRepository.save(create(scan, ctx, finding, now));
                onOpened.run();
            } else {
                // Marked observed even if the save loses a race: the finding is still present, so it
                // must not be resolved below.
                observed.add(existing.getId());
                var wasOpen = existing.getStatus() == SchemaDriftFindingStatus.OPEN;
                var refreshed = refresh(existing, scan, finding, now);
                var reopened = !wasOpen && refreshed.getStatus() == SchemaDriftFindingStatus.OPEN;
                if (saveUnlessChanged(refreshed) && reopened) {
                    onOpened.run();
                }
            }
        }

        resolveDisappeared(active, observed, result, now);
    }

    private static SchemaDriftFindingEntity create(SchemaDriftScanEntity scan, SchemaDriftScanContext ctx,
                                                   SchemaDriftDiffer.DriftFinding finding, Instant now) {
        var entity = new SchemaDriftFindingEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(ctx.organizationId());
        entity.setEnvironmentId(ctx.environmentId());
        entity.setScan(scan);
        entity.setObjectPath(finding.objectPath());
        entity.setFindingKind(finding.kind());
        entity.setExpectedValue(finding.expectedValue());
        entity.setActualValue(finding.actualValue());
        entity.setStatus(SchemaDriftFindingStatus.OPEN);
        entity.setFirstDetectedAt(now);
        entity.setLastSeenAt(now);
        return entity;
    }

    private static SchemaDriftFindingEntity refresh(SchemaDriftFindingEntity existing, SchemaDriftScanEntity scan,
                                                    SchemaDriftDiffer.DriftFinding finding, Instant now) {
        var valuesChanged = !sameValues(existing, finding);
        if (existing.getStatus() == SchemaDriftFindingStatus.RESOLVED) {
            // A new episode of a drift that had gone away. Reopened in place rather than inserted
            // again: a second row would make the natural key permanently ambiguous. firstDetectedAt
            // stays put — "first ever observed" is what makes a flapping object visible.
            existing.setStatus(SchemaDriftFindingStatus.OPEN);
            existing.setResolvedAt(null);
        } else if (existing.getStatus() == SchemaDriftFindingStatus.ACKNOWLEDGED && valuesChanged) {
            // An acknowledgement accepts the difference that was seen, not a different one: staging
            // being varchar where prod is text may be fine, but nobody accepted it becoming int4.
            existing.setStatus(SchemaDriftFindingStatus.OPEN);
        }
        existing.setScan(scan);
        existing.setExpectedValue(finding.expectedValue());
        existing.setActualValue(finding.actualValue());
        existing.setLastSeenAt(now);
        return existing;
    }

    private void resolveDisappeared(List<SchemaDriftFindingEntity> active, Set<UUID> observed,
                                    SchemaDriftDiffer.DiffResult result, Instant now) {
        if (!result.schemaLevelComplete()) {
            return;
        }
        for (var finding : active) {
            if (observed.contains(finding.getId()) || !eligible(finding, result)) {
                continue;
            }
            finding.setStatus(SchemaDriftFindingStatus.RESOLVED);
            finding.setResolvedAt(now);
            // scan is deliberately left pointing at the last scan that actually observed this
            // finding, and lastSeenAt is not bumped: both describe observation, not bookkeeping.
            saveUnlessChanged(finding);
        }
    }

    /**
     * Whether this scan could have re-observed the finding. Its table is the longest known table key
     * that prefixes its path — the path cannot simply be split on dots, because Elasticsearch and
     * BigQuery flatten nested fields into dotted column names and index names may contain dots too. A
     * path under no known table belongs to a schema one side lacks, or to a table neither side has any
     * more; the schema comparison always covers both, so it is eligible.
     */
    private static boolean eligible(SchemaDriftFindingEntity finding, SchemaDriftDiffer.DiffResult result) {
        if (result.foreignKeysSuppressed() && finding.getFindingKind() == SchemaDriftFindingKind.FOREIGN_KEY_MISMATCH) {
            return false;
        }
        var path = finding.getObjectPath().toLowerCase(Locale.ROOT);
        String table = null;
        for (var key : result.knownTableKeys()) {
            if ((path.equals(key) || path.startsWith(key + '.'))
                    && (table == null || key.length() > table.length())) {
                table = key;
            }
        }
        return table == null || result.reachedTableKeys().contains(table);
    }

    private boolean saveUnlessChanged(SchemaDriftFindingEntity finding) {
        try {
            findingRepository.save(finding);
            return true;
        } catch (OptimisticLockingFailureException ex) {
            log.debug("Drift finding {} changed during the scan; leaving it for the next one", finding.getId());
            return false;
        }
    }

    private static boolean sameValues(SchemaDriftFindingEntity existing, SchemaDriftDiffer.DriftFinding finding) {
        // Case-insensitive: a driver reporting VARCHAR where it used to report varchar is not a new
        // difference and must not reopen an acknowledgement.
        return equalsIgnoringCase(existing.getExpectedValue(), finding.expectedValue())
                && equalsIgnoringCase(existing.getActualValue(), finding.actualValue());
    }

    private static boolean equalsIgnoringCase(String left, String right) {
        return Objects.equals(left == null ? null : left.toLowerCase(Locale.ROOT),
                right == null ? null : right.toLowerCase(Locale.ROOT));
    }

    private static String naturalKey(String objectPath, SchemaDriftFindingKind kind) {
        return objectPath.toLowerCase(Locale.ROOT) + '|' + kind.name();
    }
}
