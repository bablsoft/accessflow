package com.bablsoft.accessflow.schemachange.internal;

import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import com.bablsoft.accessflow.core.api.DatabaseSchemaView;
import com.bablsoft.accessflow.core.api.DatasourceAdminService;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.schemachange.internal.config.SchemaChangeProperties;
import com.bablsoft.accessflow.schemachange.internal.persistence.repo.SchemaDriftScanRepository;
import com.bablsoft.accessflow.scheduling.api.DistributedLockService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runs one drift scan of one environment (#881). Both entry points — the scheduled job and the
 * on-demand "Scan now" — converge on {@link #runScan}, which never throws.
 *
 * <p>Two locks guard the work. The job's own {@code @SchedulerLock} keeps one replica per tick; this
 * per-environment lock keeps one scan per environment cluster-wide, which is what stops a manual
 * scan on one replica from racing the tick on another.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SchemaDriftScanService {

    /**
     * The engines whose introspectors read a catalog, and whose two consecutive reads of an unchanged
     * database therefore agree.
     *
     * <p>Deliberately an allow-list of deterministic engines rather than a deny-list of sampling
     * ones: MongoDB, Redis, Couchbase, DynamoDB and Neo4j all sample (50 documents, 1000 keys, one
     * sample key per prefix, a 50-row scan, a server-side graph sample), so diffing them would flap
     * forever on an unchanged database. A {@code DbType} added later is not applicable until somebody
     * verifies its introspector, which is the safe direction to be wrong in — the
     * {@code DefaultSqlReviewService.RELATIONAL_DIALECTS} invariant.
     */
    static final Set<DbType> DETERMINISTIC_ENGINES = EnumSet.of(
            DbType.POSTGRESQL, DbType.MYSQL, DbType.MARIADB, DbType.ORACLE, DbType.MSSQL, DbType.CUSTOM,
            DbType.CASSANDRA, DbType.SCYLLADB, DbType.ELASTICSEARCH, DbType.OPENSEARCH,
            DbType.SNOWFLAKE, DbType.BIGQUERY, DbType.DATABRICKS);

    private static final int MAX_ERROR_LENGTH = 500;

    private final SchemaDriftScanStore scanStore;
    private final SchemaDriftScanRepository scanRepository;
    private final SchemaDriftBaselineResolver baselineResolver;
    private final SchemaDriftFindingReconciler reconciler;
    private final DatasourceAdminService datasourceAdminService;
    private final DistributedLockService distributedLockService;
    private final SchemaChangeAuditWriter auditWriter;
    private final SchemaChangeProperties properties;
    private final ExecutorService schemaDriftScanExecutor;
    private final Clock clock;

    /** The cluster-wide lock one scan of one environment holds for its whole run. */
    static String lockName(UUID environmentId) {
        return "schemaDriftScan:" + environmentId;
    }

    /**
     * The scheduled path: opens the scan row and runs the diff on the calling thread. The row is
     * opened inside the lock, so a replica that loses the race writes nothing.
     *
     * @return {@link SchemaDriftScanRun#SKIPPED} when another replica holds this environment's lock
     */
    public SchemaDriftScanRun scan(SchemaDriftScanContext ctx) {
        var outcome = new AtomicReference<ScanOutcome>();
        var ran = distributedLockService.runLocked(lockName(ctx.environmentId()),
                properties.driftScanLockAtMostFor(),
                () -> outcome.set(runScan(scanStore.open(ctx).getId(), ctx, null)));
        if (!ran || outcome.get() == null) {
            return SchemaDriftScanRun.SKIPPED;
        }
        var reason = outcome.get().reason;
        return new SchemaDriftScanRun(true, SchemaDriftScanReason.isFailure(reason) ? reason : null);
    }

    /**
     * The on-demand path: the lock is taken on the calling thread so the endpoint can answer 409
     * synchronously, and only the scan itself is handed off.
     *
     * @return {@code false} when another replica holds the lock and nothing was submitted
     */
    boolean scanAsync(UUID scanId, SchemaDriftScanContext ctx, UUID actorId) {
        return distributedLockService.runLockedAsync(lockName(ctx.environmentId()),
                properties.driftScanLockAtMostFor(), schemaDriftScanExecutor,
                () -> runScan(scanId, ctx, actorId));
    }

    /**
     * The single chokepoint. Never throws: every failure becomes a reason on the scan row, and the
     * tail calls each swallow their own so a bookkeeping failure cannot be mistaken for a failed scan.
     * It deliberately stamps nothing on the pipeline's configuration — that is per pipeline, and only
     * the scheduled coordinator, having visited every environment, can say when the pipeline was
     * scanned.
     */
    private ScanOutcome runScan(UUID scanId, SchemaDriftScanContext ctx, UUID actorId) {
        var startedAt = clock.instant();
        var outcome = new ScanOutcome();
        try {
            if (!DETERMINISTIC_ENGINES.contains(ctx.dbType())) {
                // Short-circuits before any connection is opened — an inapplicable engine is not
                // merely "not diffed", it is never contacted.
                outcome.applicable = false;
                outcome.reason = SchemaDriftScanReason.ENGINE_NOT_APPLICABLE;
            } else {
                scanApplicable(scanId, ctx, startedAt, outcome);
            }
        } catch (RuntimeException ex) {
            log.error("Schema drift scan failed for environment {}", ctx.environmentId(), ex);
            outcome.reason = SchemaDriftScanReason.SCAN_FAILED + ": " + describe(ex);
        }
        finishScan(scanId, outcome);
        recordScanAudit(scanId, ctx, actorId, startedAt, outcome);
        return outcome;
    }

    private void scanApplicable(UUID scanId, SchemaDriftScanContext ctx, Instant startedAt, ScanOutcome outcome) {
        // Baseline first: when there is none (the lowest rung, the designated rung scanning itself, no
        // snapshot yet) the scanned database is never contacted just to record that.
        var resolution = baselineResolver.resolve(ctx);
        if (resolution.baseline() == null) {
            // Zero findings with a reason — and no resolve sweep: nothing was compared.
            outcome.reason = resolution.reasonCode();
            return;
        }
        var target = introspectTarget(ctx, outcome);
        if (target == null) {
            return;
        }
        // Computed once, so two slow introspections simply leave the table loop less budget.
        var deadline = startedAt.plus(properties.driftScanTimeBudget());
        var result = SchemaDriftDiffer.diff(resolution.baseline(), target,
                new SchemaDriftDiffer.DiffLimits(properties.driftMaxTablesPerScan(),
                        properties.driftMaxFindingsPerScan()),
                () -> !clock.instant().isBefore(deadline));
        outcome.partial = result.partial();
        if (result.foreignKeysSuppressed()) {
            outcome.reason = SchemaDriftScanReason.FK_COMPARISON_SUPPRESSED;
        }
        var scan = scanRepository.findById(scanId).orElseThrow();
        reconciler.reconcile(scan, ctx, result, clock.instant());
    }

    private DatabaseSchemaView introspectTarget(SchemaDriftScanContext ctx, ScanOutcome outcome) {
        try {
            return datasourceAdminService.introspectSchemaForSystem(ctx.datasourceId(), ctx.organizationId());
        } catch (RuntimeException ex) {
            log.warn("Could not introspect the drift target datasource {} for environment {}: {}",
                    ctx.datasourceId(), ctx.environmentId(), ex.getMessage());
            outcome.reason = SchemaDriftScanReason.TARGET_INTROSPECTION_FAILED + ": " + describe(ex);
            return null;
        }
    }

    private void finishScan(UUID scanId, ScanOutcome outcome) {
        try {
            outcome.findingsCount = scanStore.finish(scanId, outcome.applicable, outcome.partial,
                    truncate(outcome.reason));
        } catch (RuntimeException ex) {
            log.error("Could not finish schema drift scan {}", scanId, ex);
        }
    }

    private void recordScanAudit(UUID scanId, SchemaDriftScanContext ctx, UUID actorId, Instant startedAt,
                                 ScanOutcome outcome) {
        var metadata = new HashMap<String, Object>();
        metadata.put("environment_id", ctx.environmentId().toString());
        metadata.put("pipeline_id", ctx.pipelineId().toString());
        metadata.put("datasource_id", ctx.datasourceId().toString());
        metadata.put("baseline", ctx.baseline().name());
        metadata.put("applicable", outcome.applicable);
        metadata.put("partial", outcome.partial);
        metadata.put("findings_count", outcome.findingsCount);
        metadata.put("duration_ms", clock.instant().toEpochMilli() - startedAt.toEpochMilli());
        metadata.put("trigger", actorId == null ? "schedule" : "manual");
        if (outcome.reason != null) {
            metadata.put("reason", truncate(outcome.reason));
        }
        auditWriter.record(AuditAction.SCHEMA_DRIFT_SCAN_COMPLETED, AuditResourceType.SCHEMA_DRIFT_SCAN,
                scanId, ctx.organizationId(), actorId, metadata, null, null);
    }

    private static String describe(RuntimeException ex) {
        return ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
    }

    static String truncate(String value) {
        if (value == null || value.length() <= MAX_ERROR_LENGTH) {
            return value;
        }
        return value.substring(0, MAX_ERROR_LENGTH);
    }

    /** Mutable per-run tally, collected on every path so the scan row is always finished. */
    static final class ScanOutcome {
        private boolean applicable = true;
        private boolean partial;
        private int findingsCount;
        private String reason;
    }
}
