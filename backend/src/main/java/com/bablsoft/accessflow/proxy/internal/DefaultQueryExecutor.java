package com.bablsoft.accessflow.proxy.internal;

import com.bablsoft.accessflow.core.api.DatasourceConnectionDescriptor;
import com.bablsoft.accessflow.core.api.DatasourceLookupService;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.ColumnMaskDirective;
import com.bablsoft.accessflow.proxy.api.DatasourceUnavailableException;
import com.bablsoft.accessflow.core.api.QueryAffectedRowsResult;
import com.bablsoft.accessflow.core.api.QueryDryRunResult;
import com.bablsoft.accessflow.core.api.QueryEngineCatalog;
import com.bablsoft.accessflow.core.api.QueryEngineDryRunRequest;
import com.bablsoft.accessflow.core.api.QueryEngineExecutionRequest;
import com.bablsoft.accessflow.core.api.QueryEngineSampleRequest;
import com.bablsoft.accessflow.core.api.QueryExecutionRequest;
import com.bablsoft.accessflow.core.api.QueryExecutionResult;
import com.bablsoft.accessflow.proxy.api.QueryExecutor;
import com.bablsoft.accessflow.proxy.internal.dryrun.AffectedRowCounter;
import com.bablsoft.accessflow.proxy.internal.dryrun.DryRunPlanRequest;
import com.bablsoft.accessflow.proxy.internal.dryrun.DryRunPlanner;
import com.bablsoft.accessflow.proxy.internal.dryrun.DryRunPlannerRegistry;
import com.bablsoft.accessflow.core.api.SampleTableRequest;
import com.bablsoft.accessflow.core.api.SelectExecutionResult;
import com.bablsoft.accessflow.core.api.UpdateExecutionResult;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
class DefaultQueryExecutor implements QueryExecutor {

    private static final Logger log = LoggerFactory.getLogger(DefaultQueryExecutor.class);

    private final RoutingDataSourceResolver routingResolver;
    private final DatasourceLookupService datasourceLookupService;
    private final ProxyPoolProperties properties;
    private final JdbcResultRowMapper rowMapper;
    private final SqlExceptionTranslator sqlExceptionTranslator;
    private final RowSecurityRewriter rowSecurityRewriter;
    private final QueryEngineCatalog engineCatalog;
    private final DryRunPlannerRegistry dryRunPlannerRegistry;
    private final SelectResultCache resultCache;
    private final Clock clock;
    private final MessageSource messageSource;
    private final ObservationRegistry observationRegistry;

    private String msg(String key) {
        return messageSource.getMessage(key, null, LocaleContextHolder.getLocale());
    }

    @Override
    public QueryExecutionResult execute(QueryExecutionRequest request) {
        var descriptor = datasourceLookupService.findById(request.datasourceId())
                .orElseThrow(() -> new DatasourceUnavailableException(
                        msg("error.datasource_unavailable_not_found")));
        var execProps = properties.execution();
        int effectiveMaxRows = clampMaxRows(request.maxRowsOverride(),
                descriptor.maxRowsPerQuery(), execProps.maxRows());
        Duration effectiveTimeout = request.statementTimeoutOverride() != null
                ? request.statementTimeoutOverride()
                : execProps.statementTimeout();

        // accessflow.query.execute span (AF-454) — parents the datasource.acquire child span and
        // carries the engine + statement-class tags the Grafana dashboards group on.
        Observation observation = Observation.createNotStarted("accessflow.query.execute", observationRegistry)
                .lowCardinalityKeyValue("db_type", descriptor.dbType().name())
                .lowCardinalityKeyValue("query_type", request.queryType().name())
                .start();
        try (Observation.Scope ignored = observation.openScope()) {
            QueryExecutionResult result = executeInternal(request, descriptor,
                    effectiveMaxRows, effectiveTimeout, execProps, observation);
            observation.lowCardinalityKeyValue("outcome", "success");
            return result;
        } catch (RuntimeException ex) {
            observation.lowCardinalityKeyValue("outcome", "failure");
            observation.error(ex);
            throw ex;
        } finally {
            observation.stop();
        }
    }

    private QueryExecutionResult executeInternal(QueryExecutionRequest request,
                                                 DatasourceConnectionDescriptor descriptor,
                                                 int effectiveMaxRows, Duration effectiveTimeout,
                                                 ProxyPoolProperties.Execution execProps,
                                                 Observation observation) {
        if (engineCatalog.isEngineManaged(descriptor.dbType())) {
            return engineCatalog.engineFor(descriptor.dbType())
                    .execute(new QueryEngineExecutionRequest(
                            request, descriptor, effectiveMaxRows, effectiveTimeout));
        }

        Instant start = clock.instant();
        if (request.transactional()) {
            var result = executeTransactional(request, descriptor.dbType(), effectiveTimeout,
                    start);
            resultCache.invalidateTables(request.datasourceId(), request.referencedTables());
            return result;
        }
        var rewrite = rowSecurityRewriter.rewrite(request.sql(), request.rowSecurityPredicates(),
                request.softDeleteDirectives());
        // SELECT result cache (AF-457): keyed over the RLS-rewritten SQL + binds + mask/restriction
        // directives + row cap, so security scope is part of the key. SELECTs whose referenced
        // tables are unknown are never cached (no write-invalidation coverage).
        boolean cacheable = request.queryType() == QueryType.SELECT
                && !request.referencedTables().isEmpty()
                && resultCache.enabledFor(descriptor);
        String cacheKey = null;
        if (cacheable) {
            cacheKey = SelectResultCache.cacheKey(rewrite.sql(), rewrite.binds(),
                    request.restrictedColumns(), request.columnMasks(), effectiveMaxRows);
            var hit = resultCache.get(request.datasourceId(), cacheKey, durationSince(start));
            if (hit.isPresent()) {
                observation.lowCardinalityKeyValue("cache", "hit");
                return hit.get();
            }
        }
        observation.lowCardinalityKeyValue("cache", cacheable ? "miss" : "off");
        try (Connection connection = routingResolver.acquire(request.datasourceId(),
                request.queryType())) {
            connection.setReadOnly(request.queryType() == QueryType.SELECT);
            try (PreparedStatement statement = connection.prepareStatement(rewrite.sql())) {
                statement.setQueryTimeout(toTimeoutSeconds(effectiveTimeout));
                statement.setFetchSize(Math.min(effectiveMaxRows + 1, execProps.defaultFetchSize()));
                bind(statement, rewrite.binds());
                if (request.queryType() == QueryType.SELECT) {
                    var result = runSelect(statement, effectiveMaxRows,
                            execProps.maxResultBytes(), descriptor.dbType(), start,
                            request.restrictedColumns(), request.columnMasks(),
                            rewrite.appliedPolicyIds());
                    if (cacheable && result instanceof SelectExecutionResult select) {
                        resultCache.put(request.datasourceId(), cacheKey,
                                request.referencedTables(), resultCache.ttlFor(descriptor), select);
                    }
                    return result;
                }
                var result = runUpdate(statement, start, rewrite.appliedPolicyIds());
                // Any successful write drops cached SELECTs over the touched tables (unknown
                // tables ⇒ full-datasource purge, fail-safe for DDL).
                resultCache.invalidateTables(request.datasourceId(), request.referencedTables());
                return result;
            }
        } catch (SQLException ex) {
            log.debug("SQL execution failed for datasource {}: {}",
                    request.datasourceId(), ex.getMessage());
            throw sqlExceptionTranslator.translate(ex, effectiveTimeout, LocaleContextHolder.getLocale());
        }
    }

    @Override
    public SelectExecutionResult sampleTable(SampleTableRequest request) {
        var descriptor = datasourceLookupService.findById(request.datasourceId())
                .orElseThrow(() -> new DatasourceUnavailableException(
                        msg("error.datasource_unavailable_not_found")));
        var execProps = properties.execution();
        int effectiveMaxRows = clampMaxRows(request.maxRowsOverride(),
                descriptor.maxRowsPerQuery(), execProps.maxRows());
        Duration effectiveTimeout = request.statementTimeoutOverride() != null
                ? request.statementTimeoutOverride()
                : execProps.statementTimeout();

        if (engineCatalog.isEngineManaged(descriptor.dbType())) {
            return (SelectExecutionResult) engineCatalog.engineFor(descriptor.dbType())
                    .sampleTable(new QueryEngineSampleRequest(
                            request, descriptor, effectiveMaxRows, effectiveTimeout));
        }

        // Relational: build SELECT * FROM <dialect-quoted, allow-listed identifier> and run it
        // through the same row-security rewrite + column-masking path as a normal SELECT. The row
        // cap is enforced by JDBC setMaxRows (dialect-agnostic), so no per-dialect LIMIT is needed.
        var sql = "SELECT * FROM "
                + IdentifierQuoter.qualifiedTable(descriptor.dbType(), request.schema(), request.table());
        return (SelectExecutionResult) execute(new QueryExecutionRequest(
                request.datasourceId(), sql, QueryType.SELECT,
                request.maxRowsOverride(), request.statementTimeoutOverride(),
                request.restrictedColumns(), request.columnMasks(),
                request.rowSecurityPredicates(), false, null, java.util.List.of()));
    }

    @Override
    public QueryDryRunResult dryRun(QueryExecutionRequest request) {
        var descriptor = datasourceLookupService.findById(request.datasourceId())
                .orElseThrow(() -> new DatasourceUnavailableException(
                        msg("error.datasource_unavailable_not_found")));
        var execProps = properties.execution();
        Duration effectiveTimeout = request.statementTimeoutOverride() != null
                ? request.statementTimeoutOverride()
                : execProps.statementTimeout();
        String engineId = descriptor.connectorId() != null
                ? descriptor.connectorId()
                : descriptor.dbType().name().toLowerCase(java.util.Locale.ROOT);

        if (engineCatalog.isEngineManaged(descriptor.dbType())) {
            return engineCatalog.engineFor(descriptor.dbType())
                    .dryRun(new QueryEngineDryRunRequest(request, descriptor, effectiveTimeout));
        }

        DryRunPlanner planner = dryRunPlannerRegistry.forDbType(descriptor.dbType());
        if (planner == null) {
            return QueryDryRunResult.unsupported(engineId);
        }

        // EXPLAIN-class statements never execute the planned query (no ANALYZE / no SHOWPLAN exec),
        // so this is non-mutating. SELECT dry-runs prefer the read replica via the routing resolver;
        // writes plan on the primary (e.g. Oracle writes its scratch PLAN_TABLE there).
        var rewrite = rowSecurityRewriter.rewrite(request.sql(), request.rowSecurityPredicates());
        Instant start = clock.instant();
        try (Connection connection = routingResolver.acquire(request.datasourceId(),
                request.queryType())) {
            return planner.plan(new DryRunPlanRequest(connection, rewrite.sql(), rewrite.binds(),
                    request.queryType(), engineId, effectiveTimeout, rewrite.appliedPolicyIds(),
                    start, clock));
        } catch (SQLException ex) {
            log.debug("Dry-run failed for datasource {}: {}",
                    request.datasourceId(), ex.getMessage());
            throw sqlExceptionTranslator.translate(ex, effectiveTimeout,
                    LocaleContextHolder.getLocale());
        }
    }

    @Override
    public QueryAffectedRowsResult countAffectedRows(QueryExecutionRequest request) {
        var descriptor = datasourceLookupService.findById(request.datasourceId())
                .orElseThrow(() -> new DatasourceUnavailableException(
                        msg("error.datasource_unavailable_not_found")));
        Duration effectiveTimeout = request.statementTimeoutOverride() != null
                ? request.statementTimeoutOverride()
                : properties.execution().statementTimeout();
        String engineId = descriptor.connectorId() != null
                ? descriptor.connectorId()
                : descriptor.dbType().name().toLowerCase(java.util.Locale.ROOT);

        if (engineCatalog.isEngineManaged(descriptor.dbType())) {
            return engineCatalog.engineFor(descriptor.dbType())
                    .countAffectedRows(new QueryEngineDryRunRequest(request, descriptor,
                            effectiveTimeout));
        }

        var countSql = AffectedRowCounter.toCountSql(request.sql());
        if (countSql.isEmpty()) {
            return QueryAffectedRowsResult.unsupported(engineId);
        }
        Instant start = clock.instant();
        var result = execute(new QueryExecutionRequest(request.datasourceId(), countSql.get(),
                QueryType.SELECT, null, effectiveTimeout, List.of(), List.of(),
                request.rowSecurityPredicates(), false, null, List.of()));
        if (result instanceof SelectExecutionResult select
                && !select.rows().isEmpty()
                && !select.rows().get(0).isEmpty()
                && select.rows().get(0).get(0) instanceof Number count) {
            return QueryAffectedRowsResult.of(engineId, count.longValue(), durationSince(start));
        }
        return QueryAffectedRowsResult.unsupported(engineId);
    }

    private QueryExecutionResult executeTransactional(QueryExecutionRequest request, DbType dbType,
                                                      Duration effectiveTimeout, Instant start) {
        var appliedPolicyIds = new java.util.LinkedHashSet<java.util.UUID>();
        // Rewrite every statement first; only rewrite-no-op statements (no RLS/soft-delete binds,
        // SQL unchanged) are candidates for INSERT batching (AF-457).
        var statements = request.statements();
        var rewrites = new RowSecurityRewriter.RewriteResult[statements.size()];
        var batchable = new boolean[statements.size()];
        for (int i = 0; i < statements.size(); i++) {
            rewrites[i] = rowSecurityRewriter.rewrite(statements.get(i),
                    request.rowSecurityPredicates(), request.softDeleteDirectives());
            appliedPolicyIds.addAll(rewrites[i].appliedPolicyIds());
            batchable[i] = rewrites[i].binds().isEmpty()
                    && rewrites[i].sql().equals(statements.get(i));
        }
        var steps = BatchInsertPlanner.plan(statements, batchable);
        int chunkSize = properties.execution().insertBatchChunkSize();
        try (Connection connection = routingResolver.acquire(request.datasourceId(),
                QueryType.OTHER)) {
            connection.setReadOnly(false);
            connection.setAutoCommit(false);
            long totalAffected = 0;
            try {
                for (var step : steps) {
                    totalAffected += switch (step) {
                        case BatchInsertPlanner.SingleStep single ->
                                runTransactionalStatement(connection,
                                        rewrites[single.statementIndex()], effectiveTimeout);
                        case BatchInsertPlanner.BatchStep batch ->
                                runInsertBatch(connection, batch, dbType, effectiveTimeout,
                                        chunkSize);
                    };
                }
                connection.commit();
            } catch (SQLException ex) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackEx) {
                    ex.addSuppressed(rollbackEx);
                }
                throw ex;
            }
            return new UpdateExecutionResult(totalAffected, durationSince(start), appliedPolicyIds);
        } catch (SQLException ex) {
            log.debug("Transactional SQL execution failed for datasource {}: {}",
                    request.datasourceId(), ex.getMessage());
            throw sqlExceptionTranslator.translate(ex, effectiveTimeout, LocaleContextHolder.getLocale());
        }
    }

    private long runTransactionalStatement(Connection connection,
                                           RowSecurityRewriter.RewriteResult rewrite,
                                           Duration effectiveTimeout) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(rewrite.sql())) {
            statement.setQueryTimeout(toTimeoutSeconds(effectiveTimeout));
            bind(statement, rewrite.binds());
            return statement.executeLargeUpdate();
        }
    }

    /**
     * Executes one homogeneous INSERT run as a single {@code PreparedStatement} with
     * {@code addBatch()} per row, flushing {@code executeLargeBatch()} every {@code chunkSize}
     * rows. {@code SUCCESS_NO_INFO} counts as one affected row.
     */
    private long runInsertBatch(Connection connection, BatchInsertPlanner.BatchStep batch,
                                DbType dbType, Duration effectiveTimeout, int chunkSize)
            throws SQLException {
        long affected = 0;
        try (PreparedStatement statement = connection.prepareStatement(batch.templateSql())) {
            statement.setQueryTimeout(toTimeoutSeconds(effectiveTimeout));
            int pending = 0;
            for (var row : batch.rowBinds()) {
                bindBatchRow(statement, row, dbType);
                statement.addBatch();
                pending++;
                if (pending >= chunkSize) {
                    affected += sumBatchCounts(statement.executeLargeBatch());
                    pending = 0;
                }
            }
            if (pending > 0) {
                affected += sumBatchCounts(statement.executeLargeBatch());
            }
        }
        return affected;
    }

    /**
     * Binds one batched-INSERT row. String literals came out of SQL text where the server infers
     * the column type; PostgreSQL types a plain {@code setString} bind as {@code varchar} and then
     * rejects it against uuid/jsonb/enum columns (42804), so PG strings are sent with an
     * unspecified type ({@code Types.OTHER}) to preserve literal semantics.
     */
    private static void bindBatchRow(PreparedStatement statement, List<Object> row, DbType dbType)
            throws SQLException {
        for (int i = 0; i < row.size(); i++) {
            Object value = row.get(i);
            if (value instanceof String text && dbType == DbType.POSTGRESQL) {
                statement.setObject(i + 1, text, java.sql.Types.OTHER);
            } else {
                statement.setObject(i + 1, value);
            }
        }
    }

    private static long sumBatchCounts(long[] counts) {
        long sum = 0;
        for (long count : counts) {
            sum += count == java.sql.Statement.SUCCESS_NO_INFO ? 1 : Math.max(count, 0);
        }
        return sum;
    }

    private QueryExecutionResult runSelect(PreparedStatement statement, int effectiveMaxRows,
                                           long maxResultBytes, DbType dbType, Instant start,
                                           List<String> restrictedColumns,
                                           List<ColumnMaskDirective> columnMasks,
                                           java.util.Set<java.util.UUID> appliedRowSecurityPolicyIds)
            throws SQLException {
        statement.setMaxRows(effectiveMaxRows + 1);
        try (var resultSet = statement.executeQuery()) {
            SelectExecutionResult result = rowMapper.materialize(resultSet, effectiveMaxRows,
                    maxResultBytes, dbType, durationSince(start), restrictedColumns, columnMasks);
            return appliedRowSecurityPolicyIds.isEmpty()
                    ? result
                    : result.withRowSecurityPolicyIds(appliedRowSecurityPolicyIds);
        }
    }

    private UpdateExecutionResult runUpdate(PreparedStatement statement, Instant start,
                                            java.util.Set<java.util.UUID> appliedRowSecurityPolicyIds)
            throws SQLException {
        long affected = statement.executeLargeUpdate();
        return new UpdateExecutionResult(affected, durationSince(start), appliedRowSecurityPolicyIds);
    }

    private static void bind(PreparedStatement statement, List<Object> binds) throws SQLException {
        for (int i = 0; i < binds.size(); i++) {
            statement.setObject(i + 1, binds.get(i));
        }
    }

    private Duration durationSince(Instant start) {
        return Duration.between(start, clock.instant());
    }

    private static int clampMaxRows(Integer override, int datasourceCap, int globalCap) {
        int candidate = override != null ? override : datasourceCap;
        return Math.min(candidate, globalCap);
    }

    private static int toTimeoutSeconds(Duration timeout) {
        long seconds = timeout.toSeconds();
        if (seconds <= 0) {
            return 1;
        }
        return (int) Math.min(seconds, Integer.MAX_VALUE);
    }
}
