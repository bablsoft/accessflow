package com.bablsoft.accessflow.engine.couchbase;

import com.bablsoft.accessflow.core.api.DatasourceConnectionDescriptor;
import com.bablsoft.accessflow.core.api.InvalidSqlException;
import com.bablsoft.accessflow.core.api.QueryAffectedRowsResult;
import com.bablsoft.accessflow.core.api.QueryDryRunResult;
import com.bablsoft.accessflow.core.api.QueryExecutionRequest;
import com.bablsoft.accessflow.core.api.QueryExecutionResult;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.SampleTableRequest;
import com.bablsoft.accessflow.core.api.SelectExecutionResult;
import com.bablsoft.accessflow.core.api.UnrewritableRowSecurityException;
import com.bablsoft.accessflow.core.api.UpdateExecutionResult;
import com.bablsoft.accessflow.engine.couchbase.CouchbaseRowSecurityApplier.Applied;
import com.couchbase.client.core.error.CouchbaseException;
import com.couchbase.client.java.Scope;
import com.couchbase.client.java.json.JsonObject;
import com.couchbase.client.java.query.QueryMetrics;
import com.couchbase.client.java.query.QueryOptions;
import com.couchbase.client.java.query.QueryScanConsistency;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Executes a SQL++ statement for a {@code COUCHBASE} datasource — the document-engine analogue of
 * the host's JDBC execution path. Re-parses the submitted query, applies row-security predicates
 * (rewritten SQL + named parameters, never concatenated values), and runs the statement through
 * the bucket's default-scope query context. SELECTs stream through the reactive API capped at
 * {@code maxRows + 1} to detect truncation without buffering unbounded results, then map through
 * {@link CouchbaseResultMapper} (which applies column masking). DML returns the query service's
 * {@code mutationCount}; DDL returns 0 affected rows. The host-computed statement timeout is
 * applied per query via {@code QueryOptions.timeout}.
 */
class CouchbaseQueryExecutor {

    private final CouchbaseClusterManager clusterManager;
    private final CouchbaseQueryParser parser;
    private final CouchbaseRowSecurityApplier rowSecurityApplier;
    private final CouchbaseResultMapper resultMapper;
    private final CouchbaseExceptionTranslator exceptionTranslator;
    private final QueryScanConsistency scanConsistency;
    private final Clock clock;

    CouchbaseQueryExecutor(CouchbaseClusterManager clusterManager, CouchbaseQueryParser parser,
                           CouchbaseRowSecurityApplier rowSecurityApplier,
                           CouchbaseResultMapper resultMapper,
                           CouchbaseExceptionTranslator exceptionTranslator,
                           QueryScanConsistency scanConsistency, Clock clock) {
        this.clusterManager = clusterManager;
        this.parser = parser;
        this.rowSecurityApplier = rowSecurityApplier;
        this.resultMapper = resultMapper;
        this.exceptionTranslator = exceptionTranslator;
        this.scanConsistency = scanConsistency;
        this.clock = clock;
    }

    QueryExecutionResult execute(QueryExecutionRequest request,
                                 DatasourceConnectionDescriptor descriptor, int maxRows,
                                 Duration timeout) {
        var start = clock.instant();
        var statement = parser.parseStatement(request.sql());
        var applied = rowSecurityApplier.apply(statement, request.rowSecurityPredicates());
        var scope = clusterManager.defaultScope(descriptor);
        try {
            if (statement.kind().isRead()) {
                var rows = fetchRows(scope, applied, maxRows, timeout);
                var result = resultMapper.materialize(rows, unwrapKey(statement), maxRows,
                        durationSince(start), request.restrictedColumns(), request.columnMasks());
                return applied.appliedPolicyIds().isEmpty()
                        ? result
                        : result.withRowSecurityPolicyIds(applied.appliedPolicyIds());
            }
            long affected = runMutation(scope, applied, timeout);
            return new UpdateExecutionResult(affected, durationSince(start),
                    applied.appliedPolicyIds());
        } catch (CouchbaseException ex) {
            throw exceptionTranslator.translate(ex, timeout);
        }
    }

    QueryDryRunResult dryRun(QueryExecutionRequest request,
                             DatasourceConnectionDescriptor descriptor, Duration timeout) {
        var start = clock.instant();
        var statement = parser.parseStatement(request.sql());
        // Index/scope/collection DDL has no meaningful EXPLAIN plan — degrade gracefully.
        if (statement.kind() == CouchbaseStatementKind.DDL) {
            return QueryDryRunResult.unsupported(CouchbaseQueryEngine.ENGINE_ID);
        }
        var applied = rowSecurityApplier.apply(statement, request.rowSecurityPredicates());
        var scope = clusterManager.defaultScope(descriptor);
        try {
            // EXPLAIN returns the plan without executing the statement (read-only, non-mutating).
            var result = scope.query("EXPLAIN " + applied.sql(),
                    options(applied, timeout).readonly(true));
            var rows = result.rowsAs(byte[].class);
            Object planRow = rows.isEmpty() ? null : CouchbaseJson.parseRow(rows.get(0));
            var plan = CouchbasePlanMapper.toPlan(planRow);
            return QueryDryRunResult.of(CouchbaseQueryEngine.ENGINE_ID, statement.kind().queryType(),
                    CouchbasePlanMapper.estimatedRows(planRow), plan,
                    planRow == null ? null : planRow.toString(), applied.appliedPolicyIds(),
                    durationSince(start));
        } catch (CouchbaseException ex) {
            throw exceptionTranslator.translate(ex, timeout);
        }
    }

    /**
     * Governed affected-row count (issue AF-624): the submitted UPDATE / DELETE is rewritten by
     * {@link CouchbaseCountRewriter} into a {@code SELECT COUNT(*)} over the same keyspace and
     * WHERE clause, the request's row-security directives are spliced into the count statement
     * exactly as {@link #execute} would splice them, and the count runs read-only through the
     * bucket's default-scope query context with the same scan consistency and timeout as a normal
     * read. Shape reasons never throw — non-UPDATE/DELETE types, parse failures, and every shape
     * the rewriter or the row-security splicer rejects degrade to {@code unsupported}; genuine
     * server errors propagate through the usual exception translation, like {@link #dryRun}.
     */
    QueryAffectedRowsResult countAffectedRows(QueryExecutionRequest request,
                                              DatasourceConnectionDescriptor descriptor,
                                              Duration timeout) {
        var start = clock.instant();
        if (request.queryType() != QueryType.UPDATE && request.queryType() != QueryType.DELETE) {
            return QueryAffectedRowsResult.unsupported(CouchbaseQueryEngine.ENGINE_ID);
        }
        Applied applied;
        try {
            var countSql = CouchbaseCountRewriter.toCountStatement(
                    parser.parseStatement(request.sql()));
            if (countSql == null) {
                return QueryAffectedRowsResult.unsupported(CouchbaseQueryEngine.ENGINE_ID);
            }
            applied = rowSecurityApplier.apply(parser.parseStatement(countSql),
                    request.rowSecurityPredicates());
        } catch (InvalidSqlException | UnrewritableRowSecurityException ex) {
            return QueryAffectedRowsResult.unsupported(CouchbaseQueryEngine.ENGINE_ID);
        }
        var scope = clusterManager.defaultScope(descriptor);
        try {
            var result = scope.query(applied.sql(), options(applied, timeout).readonly(true));
            var count = extractCount(result.rowsAs(byte[].class));
            if (count == null) {
                return QueryAffectedRowsResult.unsupported(CouchbaseQueryEngine.ENGINE_ID);
            }
            return QueryAffectedRowsResult.of(CouchbaseQueryEngine.ENGINE_ID, count,
                    durationSince(start));
        } catch (CouchbaseException ex) {
            throw exceptionTranslator.translate(ex, timeout);
        }
    }

    /** The {@code af_count} value of the single COUNT(*) row, or {@code null} when malformed. */
    private static Long extractCount(List<byte[]> rows) {
        if (rows.isEmpty()) {
            return null;
        }
        if (CouchbaseJson.parseRow(rows.get(0)) instanceof Map<?, ?> row
                && row.get(CouchbaseCountRewriter.COUNT_ALIAS) instanceof Number count) {
            return count.longValue();
        }
        return null;
    }

    SelectExecutionResult sampleTable(SampleTableRequest request,
                                      DatasourceConnectionDescriptor descriptor, int maxRows,
                                      Duration timeout) {
        // Sample = SELECT * FROM `keyspace`, funneled through the same parse + row-security + masking
        // pipeline as execute() so parity is automatic. The keyspace comes from introspection
        // (allow-listed); backticks are doubled defensively.
        var sql = "SELECT * FROM `" + request.table().replace("`", "``") + "`";
        var execRequest = new QueryExecutionRequest(request.datasourceId(), sql, QueryType.SELECT,
                null, null, request.restrictedColumns(), request.columnMasks(),
                request.rowSecurityPredicates(), false, null);
        return (SelectExecutionResult) execute(execRequest, descriptor, maxRows, timeout);
    }

    /** Stream rows reactively, stopping at {@code maxRows + 1} (the truncation sentinel). */
    private List<Object> fetchRows(Scope scope, Applied applied, int maxRows,
                                   Duration timeout) {
        var raw = scope.reactive()
                .query(applied.sql(), options(applied, timeout).readonly(true))
                .flatMapMany(result -> result.rowsAs(byte[].class))
                .take(maxRows + 1L)
                .collectList()
                .block();
        var rows = new ArrayList<Object>(raw == null ? 0 : raw.size());
        if (raw != null) {
            for (var bytes : raw) {
                rows.add(CouchbaseJson.parseRow(bytes));
            }
        }
        return rows;
    }

    private long runMutation(Scope scope, Applied applied, Duration timeout) {
        var result = scope.query(applied.sql(), options(applied, timeout).metrics(true));
        return result.metaData().metrics().map(QueryMetrics::mutationCount).orElse(0L);
    }

    private QueryOptions options(Applied applied, Duration timeout) {
        var options = QueryOptions.queryOptions().timeout(timeout)
                .scanConsistency(scanConsistency);
        if (!applied.parameters().isEmpty()) {
            options = options.parameters(JsonObject.from(applied.parameters()));
        }
        return options;
    }

    /** The {@code SELECT *} wrapper key — the FROM alias, or the target collection name. */
    private static String unwrapKey(CouchbaseStatement statement) {
        if (statement.targetAlias() != null) {
            return statement.targetAlias();
        }
        return statement.target() != null ? statement.target().lastSegment() : null;
    }

    private Duration durationSince(Instant start) {
        return Duration.between(start, clock.instant());
    }
}
