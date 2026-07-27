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
import com.bablsoft.accessflow.discovery.api.DiscoveryScanAlreadyRunningException;
import com.bablsoft.accessflow.discovery.internal.config.DiscoveryProperties;
import com.bablsoft.accessflow.discovery.internal.detect.ValueDetector;
import com.bablsoft.accessflow.discovery.internal.persistence.entity.DiscoveryFindingEntity;
import com.bablsoft.accessflow.discovery.internal.persistence.entity.DiscoveryScanConfigEntity;
import com.bablsoft.accessflow.discovery.internal.persistence.repo.DiscoveryFindingRepository;
import com.bablsoft.accessflow.discovery.internal.persistence.repo.DiscoveryScanConfigRepository;
import com.bablsoft.accessflow.proxy.api.QueryExecutor;
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
import java.util.concurrent.ConcurrentHashMap;

/**
 * The discovery scan pipeline (AF-623): enumerate tables via system-lane introspection, read a
 * bounded raw sample per table through {@link QueryExecutor#sampleTable} (raw values live only on
 * this method's stack — findings persist a redacted sample only), run the local
 * {@link ValueDetector} pipeline, optionally the AI pass (redacted samples only), and upsert
 * PENDING findings. CONFIRMED/DISMISSED rows are never touched — a dismissal permanently
 * suppresses the proposal.
 *
 * <p>The in-flight guard is per node; cluster races between a "Scan now" and the scheduled job
 * are harmless because upserts are idempotent and the natural-key unique index breaks ties.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DiscoveryScanService {

    static final int MIN_SAMPLE_COUNT = 5;
    static final double MIN_MATCH_RATIO = 0.30;
    private static final int MAX_AI_SAMPLES_PER_COLUMN = 5;
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
    private final Clock clock;

    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();

    /** Best-effort pre-check for the on-demand trigger; {@link #scan} re-checks atomically. */
    boolean isInFlight(UUID datasourceId) {
        return inFlight.contains(datasourceId);
    }

    /**
     * Runs a full scan of the datasource synchronously. Never throws once started — all failures
     * are logged, stamped on the config row, and audited. {@code actorId} is {@code null} for the
     * scheduled path.
     *
     * @throws DiscoveryScanAlreadyRunningException when a scan for the datasource is already in
     *         flight on this node
     */
    public void scan(UUID datasourceId, UUID organizationId, UUID actorId) {
        if (!inFlight.add(datasourceId)) {
            throw new DiscoveryScanAlreadyRunningException(datasourceId);
        }
        try {
            runScan(datasourceId, organizationId, actorId);
        } finally {
            inFlight.remove(datasourceId);
        }
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
        } catch (RuntimeException ex) {
            log.error("Discovery scan failed for datasource {}", datasourceId, ex);
            error = truncate(ex.getMessage() == null ? ex.getClass().getSimpleName()
                    : ex.getMessage());
        }
        stampConfig(datasourceId, organizationId, error);
        recordScanAudit(datasourceId, organizationId, actorId, startedAt, stats, error);
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
        var columnValues = collectStringColumns(select);
        var now = clock.instant();
        var aiCandidates = new ArrayList<DataDiscoveryAiService.DiscoveryColumnContext>();
        var columnTypes = columnTypesByName(target);

        for (var entry : columnValues.entrySet()) {
            var columnName = entry.getKey();
            var values = entry.getValue();
            if (values.size() < MIN_SAMPLE_COUNT
                    || isMasked(target, columnName, maskedColumnRefs)) {
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
            if (aiEnabled && !proposed
                    && !isTaggedAnyClassification(taggedKeys, target, columnName)) {
                aiCandidates.add(new DataDiscoveryAiService.DiscoveryColumnContext(columnName,
                        columnTypes.get(columnName.toLowerCase(Locale.ROOT)),
                        redactForAi(values)));
            }
        }

        if (aiEnabled && !aiCandidates.isEmpty()
                && stats.aiTablesUsed < properties.maxAiTablesPerScan()) {
            stats.aiTablesUsed++;
            runAiPass(datasourceId, organizationId, target, aiCandidates, columnValues,
                    taggedKeys, stats);
        }
    }

    private void runAiPass(UUID datasourceId, UUID organizationId, TableTarget target,
                           List<DataDiscoveryAiService.DiscoveryColumnContext> candidates,
                           Map<String, List<String>> columnValues, Set<String> taggedKeys,
                           ScanStats stats) {
        var suggestions = dataDiscoveryAiService.classifyColumns(organizationId,
                new DataDiscoveryAiService.DiscoveryTableContext(target.qualifiedName(),
                        candidates));
        var now = clock.instant();
        for (var suggestion : suggestions) {
            if (isTagged(taggedKeys, target, suggestion.columnName(),
                    suggestion.classification())) {
                continue;
            }
            var values = columnValues.getOrDefault(suggestion.columnName(), List.of());
            var sample = values.isEmpty() ? null
                    : ColumnMasker.apply(MaskingStrategy.FORMAT_PRESERVING, values.getFirst(),
                            Map.of());
            upsertFinding(datasourceId, organizationId, target, suggestion.columnName(),
                    suggestion.classification(), DiscoveryDetector.AI, suggestion.confidence(),
                    sample, suggestion.rationale(), 0, values.size(), now, stats);
            stats.aiSuggestions++;
        }
    }

    private void upsertFinding(UUID datasourceId, UUID organizationId, TableTarget target,
                               String columnName, DataClassification classification,
                               DiscoveryDetector detector, int confidence, String sampleRedacted,
                               String rationale, int matchCount, int sampleCount, Instant now,
                               ScanStats stats) {
        var existing = findingRepository.findByNaturalKey(organizationId, datasourceId,
                target.schemaName(), target.tableName(), columnName, classification, detector)
                .orElse(null);
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
            stats.findingsCreated++;
            return;
        }
        if (existing.getStatus() != DiscoveryFindingStatus.PENDING) {
            return;
        }
        existing.setConfidence(confidence);
        existing.setSampleRedacted(sampleRedacted);
        existing.setRationale(rationale);
        existing.setMatchCount(matchCount);
        existing.setSampleCount(sampleCount);
        existing.setLastDetectedAt(now);
        findingRepository.save(existing);
        stats.findingsRefreshed++;
    }

    /** Non-blank string values per column name, preserving the result's column order. */
    private static Map<String, List<String>> collectStringColumns(SelectExecutionResult select) {
        var byColumn = new java.util.LinkedHashMap<String, List<String>>();
        var columns = select.columns();
        for (var column : columns) {
            byColumn.put(column.name(), new ArrayList<>());
        }
        for (var row : select.rows()) {
            for (var i = 0; i < columns.size() && i < row.size(); i++) {
                if (row.get(i) instanceof String s && !s.isBlank()) {
                    byColumn.get(columns.get(i).name()).add(s);
                }
            }
        }
        return byColumn;
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

    /** Mirrors the executor's mask-matching precedence: schema.table.column, table.column, column. */
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
        private int aiSuggestions;
        private int aiTablesUsed;
        private boolean partial;
    }
}
