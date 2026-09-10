package com.bablsoft.accessflow.discovery.internal;

import com.bablsoft.accessflow.ai.api.DataDiscoveryAiService;
import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditEntry;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import com.bablsoft.accessflow.core.api.ColumnMasker;
import com.bablsoft.accessflow.core.api.DataClassification;
import com.bablsoft.accessflow.core.api.DataClassificationQueryService;
import com.bablsoft.accessflow.core.api.DatabaseSchemaView;
import com.bablsoft.accessflow.core.api.DatasourceAdminService;
import com.bablsoft.accessflow.core.api.MaskingPolicyAdminService;
import com.bablsoft.accessflow.core.api.MaskingStrategy;
import com.bablsoft.accessflow.core.api.SampleTableRequest;
import com.bablsoft.accessflow.core.api.SelectExecutionResult;
import com.bablsoft.accessflow.discovery.api.DiscoveryDetector;
import com.bablsoft.accessflow.discovery.api.DiscoveryFindingStatus;
import com.bablsoft.accessflow.discovery.internal.config.DiscoveryProperties;
import com.bablsoft.accessflow.discovery.internal.detect.ValueDetector;
import com.bablsoft.accessflow.discovery.internal.persistence.entity.DiscoveryFindingEntity;
import com.bablsoft.accessflow.discovery.internal.persistence.entity.DiscoveryScanConfigEntity;
import com.bablsoft.accessflow.discovery.internal.persistence.repo.DiscoveryFindingRepository;
import com.bablsoft.accessflow.discovery.internal.persistence.repo.DiscoveryScanConfigRepository;
import com.bablsoft.accessflow.proxy.api.QueryExecutor;
import com.bablsoft.accessflow.scheduling.api.DistributedLockService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;

/**
 * The discovery scan pipeline (AF-623): enumerate tables via system-lane introspection, read a
 * bounded raw sample per table through {@link QueryExecutor#sampleTable} (raw values live only on
 * this method's stack — findings persist a redacted sample only), run the local
 * {@link ValueDetector} pipeline, optionally the AI pass (redacted samples only), and upsert
 * PENDING findings. CONFIRMED/DISMISSED rows are never touched — a dismissal permanently
 * suppresses the proposal.
 *
 * <p>One scan per datasource at a time, cluster-wide (AF-660): both entry points take the
 * {@code discoveryScan:<datasourceId>} lock through {@link DistributedLockService}, so a "Scan now"
 * on one replica and the scheduled job on another cannot sample the same customer database at once.
 * A caller that does not get the lock is told so — it never silently queues behind the winner.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DiscoveryScanService {

    static final int MIN_SAMPLE_COUNT = 5;
    static final double MIN_MATCH_RATIO = 0.30;
    private static final int MAX_AI_SAMPLES_PER_COLUMN = 5;
    /**
     * Columns offered to the AI pass for one table. Flattening raises the candidate count from a
     * table's column list to its nested-path count (AF-658), and the whole list goes into a single
     * prompt — past the model's context window the provider errors, the fail-safe lane swallows it,
     * and the scan pays for a call that yields nothing.
     */
    private static final int MAX_AI_COLUMNS_PER_TABLE = 50;
    private static final int MAX_ERROR_LENGTH = 500;
    private static final Map<String, String> PARTIAL_PARAMS = Map.of("visible_suffix", "4");

    private final DiscoveryScanConfigRepository configRepository;
    private final DiscoveryFindingRepository findingRepository;
    private final DatasourceAdminService datasourceAdminService;
    private final DataClassificationQueryService dataClassificationQueryService;
    private final MaskingPolicyAdminService maskingPolicyAdminService;
    private final QueryExecutor queryExecutor;
    private final DataDiscoveryAiService dataDiscoveryAiService;
    private final AuditLogService auditLogService;
    private final DiscoveryProperties properties;
    private final NestedValueFlattener nestedValueFlattener;
    private final DiscoveryStaleSweepService staleSweepService;
    private final DistributedLockService distributedLockService;
    private final ExecutorService discoveryScanExecutor;
    private final Clock clock;

    /** The cluster-wide lock one scan of one datasource holds for its whole run. */
    static String lockName(UUID datasourceId) {
        return "discoveryScan:" + datasourceId;
    }

    /**
     * Runs a full scan of the datasource on the calling thread. Never throws once started — all
     * failures are logged, stamped on the config row, and audited. {@code actorId} is {@code null}
     * for the scheduled path.
     *
     * @return {@code true} when the scan ran; {@code false} when another replica is already
     *         scanning this datasource and nothing was done
     */
    public boolean scan(UUID datasourceId, UUID organizationId, UUID actorId) {
        return distributedLockService.runLocked(lockName(datasourceId),
                properties.scanLockAtMostFor(),
                () -> runScan(datasourceId, organizationId, actorId));
    }

    /**
     * Same scan, run on the discovery executor instead of the caller's thread — the on-demand
     * path, where the HTTP request must return 202 or 409 immediately. The lock is taken
     * synchronously, so the answer is accurate cluster-wide rather than a guess.
     *
     * @return {@code true} when the scan was started; {@code false} when another replica is
     *         already scanning this datasource and nothing was started
     */
    public boolean scanAsync(UUID datasourceId, UUID organizationId, UUID actorId) {
        return distributedLockService.runLockedAsync(lockName(datasourceId),
                properties.scanLockAtMostFor(), discoveryScanExecutor,
                () -> runScan(datasourceId, organizationId, actorId));
    }

    private void runScan(UUID datasourceId, UUID organizationId, UUID actorId) {
        var startedAt = clock.instant();
        var stats = new ScanStats();
        String error = null;
        try {
            var config = configRepository.findByDatasourceIdAndOrganizationId(datasourceId,
                    organizationId).orElse(null);
            var sampleSize = config == null ? 100 : config.getSampleSize();
            var aiEnabled = config != null && config.isAiClassificationEnabled();

            var schemaView = datasourceAdminService.introspectSchemaForSystem(datasourceId,
                    organizationId);
            var targets = flattenTargets(schemaView);
            if (targets.size() > properties.maxTablesPerScan()) {
                stats.tablesSkipped = targets.size() - properties.maxTablesPerScan();
                targets = targets.subList(0, properties.maxTablesPerScan());
                stats.partial = true;
                log.info("Discovery scan for datasource {} capped at {} tables ({} skipped)",
                        datasourceId, properties.maxTablesPerScan(), stats.tablesSkipped);
            }

            var taggedKeys = loadTaggedKeys(datasourceId, organizationId);
            var maskedColumnRefs = loadMaskedColumnRefs(datasourceId, organizationId);
            var deadline = startedAt.plus(properties.scanTimeBudget());

            for (var i = 0; i < targets.size(); i++) {
                var target = targets.get(i);
                if (!clock.instant().isBefore(deadline)) {
                    stats.partial = true;
                    stats.tablesSkipped += targets.size() - i;
                    log.warn("Discovery scan for datasource {} hit its time budget after {} tables",
                            datasourceId, stats.tablesScanned);
                    break;
                }
                try {
                    scanTable(datasourceId, organizationId, target, sampleSize, aiEnabled,
                            taggedKeys, maskedColumnRefs, stats);
                    stats.tablesScanned++;
                } catch (RuntimeException ex) {
                    stats.tablesFailed++;
                    log.error("Discovery scan failed for table {} of datasource {}: {}",
                            target.qualifiedName(), datasourceId, ex.getMessage());
                }
            }
            if (stats.tablesFailed > 0 && stats.tablesScanned == 0) {
                error = truncate("All " + stats.tablesFailed + " sampled tables failed");
            }
            ageStaleFindings(datasourceId, organizationId, stats);
        } catch (RuntimeException ex) {
            log.error("Discovery scan failed for datasource {}", datasourceId, ex);
            error = truncate(ex.getMessage() == null ? ex.getClass().getSimpleName()
                    : ex.getMessage());
        }
        stampConfig(datasourceId, organizationId, error);
        recordScanAudit(datasourceId, organizationId, actorId, startedAt, stats, error);
    }

    /**
     * Counts findings this run sampled but no longer proposes, retiring them as STALE at the
     * configured threshold. Deliberately swallows everything: the scan itself has already
     * succeeded by this point, and a bookkeeping failure must not be reported as a failed scan.
     */
    private void ageStaleFindings(UUID datasourceId, UUID organizationId, ScanStats stats) {
        try {
            var result = staleSweepService.sweep(datasourceId, organizationId, stats.scannedTables,
                    stats.aiScannedTables, stats.seenFindingIds);
            stats.findingsAged = result.aged();
            stats.findingsExpired = result.expired();
            stats.expiredAuditTruncated = result.expiredAuditTruncated();
        } catch (RuntimeException ex) {
            log.error("Stale-finding sweep failed for datasource {}", datasourceId, ex);
        }
    }

    private void scanTable(UUID datasourceId, UUID organizationId, TableTarget target,
                           int sampleSize, boolean aiEnabled, Set<String> taggedKeys,
                           Set<String> maskedColumnRefs, ScanStats stats) {
        var result = queryExecutor.sampleTable(new SampleTableRequest(datasourceId,
                target.schemaName(), target.tableName(), sampleSize,
                properties.sampleStatementTimeout()));
        if (!(result instanceof SelectExecutionResult select)) {
            return;
        }
        var columnValues = nestedValueFlattener.collect(select, sampleSize);
        var now = clock.instant();
        var aiCandidates = new ArrayList<DataDiscoveryAiService.DiscoveryColumnContext>();
        var columnTypes = columnTypesByName(target);
        // AF-659 eligibility: whether any column yielded enough values to run detection over, and
        // whether the AI candidate cap hid a column from the model.
        var sampled = false;
        var aiCandidatesTruncated = false;

        for (var entry : columnValues.entrySet()) {
            var columnName = entry.getKey();
            var values = entry.getValue();
            if (values.size() < MIN_SAMPLE_COUNT) {
                continue;
            }
            // A masked column still proves the sample was real, so this counts towards "sampled"
            // before the mask check skips it (AF-659).
            sampled = true;
            if (isMasked(target, columnName, maskedColumnRefs)) {
                continue;
            }
            var matches = detect(values);
            var proposed = false;
            for (var detectorEntry : matches.entrySet()) {
                var detector = detectorEntry.getKey();
                var detectorMatches = detectorEntry.getValue();
                var ratio = detectorMatches.count / (double) values.size();
                if (ratio < MIN_MATCH_RATIO) {
                    continue;
                }
                proposed = true;
                if (isTagged(taggedKeys, target, columnName, detector.classification())) {
                    continue;
                }
                upsertFinding(datasourceId, organizationId, target, columnName,
                        detector.classification(), detector.type(),
                        (int) Math.round(100.0 * ratio),
                        ColumnMasker.apply(MaskingStrategy.PARTIAL, detectorMatches.firstMatch,
                                PARTIAL_PARAMS),
                        null, detectorMatches.count, values.size(), now, stats);
            }
            if (aiEnabled && !proposed && !isTaggedAnyClassification(taggedKeys, target, columnName)
                    && aiCandidates.size() >= MAX_AI_COLUMNS_PER_TABLE) {
                // The model never sees this column, so its AI findings cannot be re-proposed and
                // the table must not age them. The flattened column order is stable, so without
                // this the same findings would be missed every scan and expire on schedule.
                aiCandidatesTruncated = true;
            }
            if (aiEnabled && !proposed && aiCandidates.size() < MAX_AI_COLUMNS_PER_TABLE
                    && !isTaggedAnyClassification(taggedKeys, target, columnName)) {
                aiCandidates.add(new DataDiscoveryAiService.DiscoveryColumnContext(columnName,
                        columnTypes.get(columnName.toLowerCase(Locale.ROOT)),
                        redactForAi(values)));
            }
        }

        if (!sampled) {
            return;
        }
        stats.scannedTables.add(DiscoveryTableKey.of(target.schemaName(), target.tableName()));

        if (aiEnabled && !aiCandidates.isEmpty()
                && stats.aiTablesUsed < properties.maxAiTablesPerScan()) {
            stats.aiTablesUsed++;
            var suggested = runAiPass(datasourceId, organizationId, target, aiCandidates,
                    columnValues, taggedKeys, stats);
            // Only a table the AI pass demonstrably answered for may age its AI findings, and only
            // when the model saw every candidate. The pass is capped well below the table cap, is
            // opt-in, and is fail-safe all the way down — a rotated key, an outage or a deleted
            // ai_config yields an empty list rather than an exception, indistinguishable from
            // "nothing sensitive here". Requiring a real suggestion keeps a provider failure from
            // retiring every AI finding in the estate; the cost is that a table where the model
            // now finds nothing at all keeps its AI findings, which is the fail-closed direction.
            if (suggested > 0 && !aiCandidatesTruncated) {
                stats.aiScannedTables.add(
                        DiscoveryTableKey.of(target.schemaName(), target.tableName()));
            }
        }
    }

    /** @return how many suggestions the model returned — 0 also means "the AI lane failed". */
    private int runAiPass(UUID datasourceId, UUID organizationId, TableTarget target,
                          List<DataDiscoveryAiService.DiscoveryColumnContext> candidates,
                          Map<String, List<String>> columnValues, Set<String> taggedKeys,
                          ScanStats stats) {
        var suggestions = dataDiscoveryAiService.classifyColumns(organizationId,
                new DataDiscoveryAiService.DiscoveryTableContext(target.qualifiedName(),
                        candidates));
        var canonicalColumns = canonicalColumnNames(columnValues);
        var now = clock.instant();
        for (var suggestion : suggestions) {
            // The model echoes the column name back, and a dot-path invites re-casing. Resolve to
            // the sampled spelling so a re-cased echo cannot open a second natural key, and drop
            // anything that resolves to no sampled column at all.
            var columnName = canonicalColumns.get(
                    suggestion.columnName().toLowerCase(Locale.ROOT));
            if (columnName == null
                    || isTagged(taggedKeys, target, columnName, suggestion.classification())) {
                continue;
            }
            var values = columnValues.getOrDefault(columnName, List.of());
            var sample = values.isEmpty() ? null
                    : ColumnMasker.apply(MaskingStrategy.FORMAT_PRESERVING, values.getFirst(),
                            Map.of());
            upsertFinding(datasourceId, organizationId, target, columnName,
                    suggestion.classification(), DiscoveryDetector.AI, suggestion.confidence(),
                    sample, suggestion.rationale(), 0, values.size(), now, stats);
            stats.aiSuggestions++;
        }
        return suggestions.size();
    }

    private void upsertFinding(UUID datasourceId, UUID organizationId, TableTarget target,
                               String columnName, DataClassification classification,
                               DiscoveryDetector detector, int confidence, String sampleRedacted,
                               String rationale, int matchCount, int sampleCount, Instant now,
                               ScanStats stats) {
        var existing = findingRepository.findByNaturalKey(organizationId, datasourceId,
                target.schemaName(), target.tableName(), columnName, classification, detector)
                .orElse(null);
        if (existing != null) {
            // Recorded before the status guard below so the sweep can never age a finding this
            // run re-proposed, whatever state the row was in when we found it.
            stats.seenFindingIds.add(existing.getId());
        }
        if (existing == null) {
            var entity = new DiscoveryFindingEntity();
            entity.setId(UUID.randomUUID());
            entity.setOrganizationId(organizationId);
            entity.setDatasourceId(datasourceId);
            entity.setSchemaName(target.schemaName());
            entity.setTableName(target.tableName());
            entity.setColumnName(columnName);
            entity.setClassification(classification);
            entity.setDetector(detector);
            entity.setConfidence(confidence);
            entity.setSampleRedacted(sampleRedacted);
            entity.setRationale(rationale);
            entity.setMatchCount(matchCount);
            entity.setSampleCount(sampleCount);
            entity.setStatus(DiscoveryFindingStatus.PENDING);
            entity.setFirstDetectedAt(now);
            entity.setLastDetectedAt(now);
            findingRepository.save(entity);
            stats.seenFindingIds.add(entity.getId());
            stats.findingsCreated++;
            return;
        }
        // CONFIRMED and DISMISSED are permanent decisions and are never reopened by a rescan.
        // STALE is not a decision — it is an aged PENDING, so re-detection revives it (AF-659).
        if (existing.getStatus() == DiscoveryFindingStatus.CONFIRMED
                || existing.getStatus() == DiscoveryFindingStatus.DISMISSED) {
            return;
        }
        var revived = existing.getStatus() == DiscoveryFindingStatus.STALE;
        existing.setStatus(DiscoveryFindingStatus.PENDING);
        existing.setMissedScanCount(0);
        existing.setConfidence(confidence);
        existing.setSampleRedacted(sampleRedacted);
        existing.setRationale(rationale);
        existing.setMatchCount(matchCount);
        existing.setSampleCount(sampleCount);
        existing.setLastDetectedAt(now);
        findingRepository.save(existing);
        stats.findingsRefreshed++;
        if (revived) {
            stats.findingsRevived++;
        }
    }

    /** Lowercased sampled column name → the spelling the sample actually used. */
    private static Map<String, String> canonicalColumnNames(Map<String, List<String>> columnValues) {
        var canonical = new HashMap<String, String>();
        for (var name : columnValues.keySet()) {
            canonical.putIfAbsent(name.toLowerCase(Locale.ROOT), name);
        }
        return canonical;
    }

    /** First-match-wins detector counts over the column's sampled values. */
    private static Map<ValueDetector, DetectorMatches> detect(List<String> values) {
        var matches = new HashMap<ValueDetector, DetectorMatches>();
        for (var value : values) {
            for (var detector : ValueDetector.ORDERED) {
                if (detector.matches(value)) {
                    matches.computeIfAbsent(detector, key -> new DetectorMatches(value)).count++;
                    break;
                }
            }
        }
        return matches;
    }

    private List<String> redactForAi(List<String> values) {
        return values.stream()
                .limit(MAX_AI_SAMPLES_PER_COLUMN)
                .map(value -> ColumnMasker.apply(MaskingStrategy.FORMAT_PRESERVING, value, Map.of()))
                .toList();
    }

    private static List<TableTarget> flattenTargets(DatabaseSchemaView schemaView) {
        var targets = new ArrayList<TableTarget>();
        for (var schema : schemaView.schemas()) {
            var schemaName = schema.name() == null || schema.name().isBlank() ? null : schema.name();
            for (var table : schema.tables()) {
                targets.add(new TableTarget(schemaName, table.name(), table.columns()));
            }
        }
        return targets;
    }

    private Map<String, String> columnTypesByName(TableTarget target) {
        var types = new HashMap<String, String>();
        for (var column : target.columns()) {
            types.put(column.name().toLowerCase(Locale.ROOT), column.type());
        }
        return types;
    }

    /**
     * Existing-tag keys as {@code table|column|classification} (lowercased), with the table part
     * both bare and schema-qualified — AF-447 tags store either form.
     */
    private Set<String> loadTaggedKeys(UUID datasourceId, UUID organizationId) {
        var keys = new HashSet<String>();
        for (var tag : dataClassificationQueryService.findByDatasource(datasourceId,
                organizationId)) {
            if (tag.columnName() == null) {
                continue;
            }
            keys.add(tagKey(tag.tableName(), tag.columnName(), tag.classification()));
        }
        return keys;
    }

    private boolean isTagged(Set<String> taggedKeys, TableTarget target, String columnName,
                             DataClassification classification) {
        if (taggedKeys.contains(tagKey(target.tableName(), columnName, classification))) {
            return true;
        }
        return target.schemaName() != null && taggedKeys.contains(
                tagKey(target.schemaName() + "." + target.tableName(), columnName, classification));
    }

    private boolean isTaggedAnyClassification(Set<String> taggedKeys, TableTarget target,
                                              String columnName) {
        for (var classification : DataClassification.values()) {
            if (isTagged(taggedKeys, target, columnName, classification)) {
                return true;
            }
        }
        return false;
    }

    private static String tagKey(String tableName, String columnName,
                                 DataClassification classification) {
        return (tableName + "|" + columnName + "|" + classification).toLowerCase(Locale.ROOT);
    }

    /** Enabled masking-policy column refs, lowercased — already-masked columns are skipped. */
    private Set<String> loadMaskedColumnRefs(UUID datasourceId, UUID organizationId) {
        var refs = new HashSet<String>();
        for (var policy : maskingPolicyAdminService.listForDatasource(datasourceId,
                organizationId)) {
            if (policy.enabled()) {
                refs.add(policy.columnRef().toLowerCase(Locale.ROOT));
            }
        }
        return refs;
    }

    /**
     * Mirrors the executor's mask-matching precedence: schema.table.column, table.column, column.
     *
     * <p>A flattened nested column (AF-658) is just a dot-path string here, sharing the ambiguous
     * {@code table.column} namespace: a pseudo-column {@code profile.email} can match a policy
     * authored for a collection named {@code profile}, and a bare {@code email} policy does not
     * suppress {@code profile.contact.email}. Both are tolerable — the scan reads an
     * <em>unmasked</em> sample, so this is only a "don't propose what is already handled" filter
     * and a miss costs a redundant PENDING finding, never bad detection.
     */
    private boolean isMasked(TableTarget target, String columnName, Set<String> maskedColumnRefs) {
        var column = columnName.toLowerCase(Locale.ROOT);
        var table = target.tableName().toLowerCase(Locale.ROOT);
        if (maskedColumnRefs.contains(column) || maskedColumnRefs.contains(table + "." + column)) {
            return true;
        }
        return target.schemaName() != null && maskedColumnRefs.contains(
                target.schemaName().toLowerCase(Locale.ROOT) + "." + table + "." + column);
    }

    private void stampConfig(UUID datasourceId, UUID organizationId, String error) {
        try {
            var config = configRepository.findByDatasourceIdAndOrganizationId(datasourceId,
                    organizationId).orElseGet(() -> {
                        var created = new DiscoveryScanConfigEntity();
                        created.setId(UUID.randomUUID());
                        created.setOrganizationId(organizationId);
                        created.setDatasourceId(datasourceId);
                        return created;
                    });
            config.setLastScanAt(clock.instant());
            config.setLastScanError(error);
            configRepository.save(config);
        } catch (RuntimeException ex) {
            log.error("Failed to stamp discovery scan outcome for datasource {}", datasourceId, ex);
        }
    }

    private void recordScanAudit(UUID datasourceId, UUID organizationId, UUID actorId,
                                 Instant startedAt, ScanStats stats, String error) {
        try {
            var metadata = new HashMap<String, Object>();
            metadata.put("datasourceId", datasourceId.toString());
            metadata.put("tablesScanned", stats.tablesScanned);
            metadata.put("tablesSkipped", stats.tablesSkipped);
            metadata.put("tablesFailed", stats.tablesFailed);
            metadata.put("findingsCreated", stats.findingsCreated);
            metadata.put("findingsRefreshed", stats.findingsRefreshed);
            metadata.put("findingsRevived", stats.findingsRevived);
            metadata.put("findingsAged", stats.findingsAged);
            metadata.put("findingsExpired", stats.findingsExpired);
            metadata.put("expiredAuditTruncated", stats.expiredAuditTruncated);
            metadata.put("aiSuggestions", stats.aiSuggestions);
            metadata.put("durationMs", Duration.between(startedAt, clock.instant()).toMillis());
            metadata.put("partial", stats.partial);
            if (error != null) {
                metadata.put("error", error);
            }
            auditLogService.record(new AuditEntry(AuditAction.DISCOVERY_SCAN_COMPLETED,
                    AuditResourceType.DATASOURCE, datasourceId, organizationId, actorId,
                    metadata, null, null));
        } catch (RuntimeException ex) {
            log.error("Audit write failed for discovery scan of datasource {}", datasourceId, ex);
        }
    }

    private static String truncate(String message) {
        return message.length() > MAX_ERROR_LENGTH
                ? message.substring(0, MAX_ERROR_LENGTH) : message;
    }

    private record TableTarget(String schemaName, String tableName,
                               List<DatabaseSchemaView.Column> columns) {

        String qualifiedName() {
            return schemaName == null ? tableName : schemaName + "." + tableName;
        }
    }

    private static final class DetectorMatches {
        private final String firstMatch;
        private int count;

        private DetectorMatches(String firstMatch) {
            this.firstMatch = firstMatch;
        }
    }

    private static final class ScanStats {
        private int tablesScanned;
        private int tablesSkipped;
        private int tablesFailed;
        private int findingsCreated;
        private int findingsRefreshed;
        private int findingsRevived;
        private int findingsAged;
        private int findingsExpired;
        private boolean expiredAuditTruncated;
        private int aiSuggestions;
        private int aiTablesUsed;
        private boolean partial;

        /** Tables this run actually sampled — the only ones whose findings may age (AF-659). */
        private final Set<DiscoveryTableKey> scannedTables = new HashSet<>();

        /** Subset of the above where the capped, opt-in AI pass also ran. */
        private final Set<DiscoveryTableKey> aiScannedTables = new HashSet<>();

        /** Findings re-proposed by this run, so the sweep leaves them alone. */
        private final Set<UUID> seenFindingIds = new HashSet<>();
    }
}
