package com.bablsoft.accessflow.engine.neo4j;

import com.bablsoft.accessflow.core.api.DatasourceConnectionDescriptor;
import com.bablsoft.accessflow.core.api.QueryExecutionRequest;
import com.bablsoft.accessflow.core.api.QueryExecutionResult;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.SampleTableRequest;
import com.bablsoft.accessflow.core.api.SelectExecutionResult;
import com.bablsoft.accessflow.core.api.UpdateExecutionResult;
import org.neo4j.driver.Query;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.neo4j.driver.TransactionConfig;
import org.neo4j.driver.exceptions.Neo4jException;
import org.neo4j.driver.summary.SummaryCounters;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Executes a Cypher statement for a {@code NEO4J} datasource — the graph analogue of the host's JDBC
 * execution path. Re-parses the submitted query, applies row-security predicates (rewritten Cypher +
 * named parameters, never concatenated values), and runs the statement in a session scoped to the
 * datasource database with the host-computed statement timeout. SELECTs collect up to
 * {@code maxRows + 1} records to detect truncation without buffering unbounded results, then map
 * through {@link Neo4jResultMapper} (which applies column masking). Writes report the sum of the
 * Bolt summary's node / relationship / property mutation counters as the affected count; DDL returns 0.
 */
class Neo4jQueryExecutor {

    private final Neo4jDriverManager driverManager;
    private final CypherQueryParser parser;
    private final Neo4jRowSecurityApplier rowSecurityApplier;
    private final Neo4jResultMapper resultMapper;
    private final Neo4jExceptionTranslator exceptionTranslator;
    private final Clock clock;

    Neo4jQueryExecutor(Neo4jDriverManager driverManager, CypherQueryParser parser,
                       Neo4jRowSecurityApplier rowSecurityApplier, Neo4jResultMapper resultMapper,
                       Neo4jExceptionTranslator exceptionTranslator, Clock clock) {
        this.driverManager = driverManager;
        this.parser = parser;
        this.rowSecurityApplier = rowSecurityApplier;
        this.resultMapper = resultMapper;
        this.exceptionTranslator = exceptionTranslator;
        this.clock = clock;
    }

    QueryExecutionResult execute(QueryExecutionRequest request,
                                 DatasourceConnectionDescriptor descriptor, int maxRows,
                                 Duration timeout) {
        var start = clock.instant();
        var statement = parser.parseStatement(request.sql());
        var applied = rowSecurityApplier.apply(statement, request.rowSecurityPredicates());
        var driver = driverManager.driver(descriptor);
        var txConfig = TransactionConfig.builder().withTimeout(timeout).build();
        var query = new Query(applied.cypher(), applied.parameters());
        try (Session session = driver.session(Neo4jConnectionProbe.sessionConfig(descriptor))) {
            var result = session.run(query, txConfig);
            if (statement.kind().isRead()) {
                var columns = result.keys();
                // Stop at maxRows + 1; the session close cancels the rest server-side rather than
                // streaming every remaining record just to discard it.
                var rows = fetchRows(result, columns.size(), maxRows);
                var mapped = resultMapper.materialize(columns, rows, maxRows, durationSince(start),
                        request.restrictedColumns(), request.columnMasks());
                return applied.appliedPolicyIds().isEmpty()
                        ? mapped
                        : mapped.withRowSecurityPolicyIds(applied.appliedPolicyIds());
            }
            var summary = result.consume();
            long affected = statement.kind() == CypherStatementKind.DDL ? 0
                    : affectedRows(summary.counters());
            return new UpdateExecutionResult(affected, durationSince(start),
                    applied.appliedPolicyIds());
        } catch (Neo4jException ex) {
            throw exceptionTranslator.translate(ex, timeout);
        }
    }

    SelectExecutionResult sampleTable(SampleTableRequest request,
                                      DatasourceConnectionDescriptor descriptor, int maxRows,
                                      Duration timeout) {
        // Sample = MATCH (n:`Label`) RETURN n, funneled through the same parse + label-scoped
        // row-security + label-aware masking pipeline as execute(). The label comes from
        // introspection (allow-listed); backticks are doubled defensively.
        var sql = "MATCH (n:`" + request.table().replace("`", "``") + "`) RETURN n";
        var execRequest = new QueryExecutionRequest(request.datasourceId(), sql, QueryType.SELECT,
                null, null, request.restrictedColumns(), request.columnMasks(),
                request.rowSecurityPredicates(), false, null);
        return (SelectExecutionResult) execute(execRequest, descriptor, maxRows, timeout);
    }

    /** Collect up to {@code maxRows + 1} records (the truncation sentinel), converting each value. */
    private static List<List<Object>> fetchRows(Result result, int columnCount, int maxRows) {
        int limit = maxRows + 1;
        var rows = new ArrayList<List<Object>>();
        var keys = result.keys();
        while (result.hasNext() && rows.size() < limit) {
            var record = result.next();
            var row = new ArrayList<>(columnCount);
            for (var key : keys) {
                row.add(Neo4jValueConverter.convert(record.get(key)));
            }
            rows.add(row);
        }
        return rows;
    }

    private static long affectedRows(SummaryCounters counters) {
        return (long) counters.nodesCreated() + counters.nodesDeleted()
                + counters.relationshipsCreated() + counters.relationshipsDeleted()
                + counters.propertiesSet();
    }

    private Duration durationSince(Instant start) {
        return Duration.between(start, clock.instant());
    }
}
