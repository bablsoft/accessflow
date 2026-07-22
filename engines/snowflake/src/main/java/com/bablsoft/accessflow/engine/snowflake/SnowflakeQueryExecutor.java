package com.bablsoft.accessflow.engine.snowflake;

import com.bablsoft.accessflow.core.api.DatasourceConnectionDescriptor;
import com.bablsoft.accessflow.core.api.EngineMessages;
import com.bablsoft.accessflow.core.api.QueryExecutionFailedException;
import com.bablsoft.accessflow.core.api.QueryExecutionRequest;
import com.bablsoft.accessflow.core.api.QueryExecutionResult;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.SampleTableRequest;
import com.bablsoft.accessflow.core.api.SelectExecutionResult;
import com.bablsoft.accessflow.core.api.UpdateExecutionResult;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Executes a Snowflake SQL statement for a {@code SNOWFLAKE} datasource — the warehouse analogue
 * of the host's JDBC execution path, but engine-managed: re-parse, apply row-security predicates
 * (rewritten SQL + positional {@code ?} parameters, never concatenated), open a fresh short-lived
 * connection, and run a {@link PreparedStatement} with the host-computed statement timeout. Reads
 * cap the driver at {@code maxRows + 1} (the truncation sentinel) and map through
 * {@link SnowflakeResultMapper} (which applies column masking). A deny-all row-security result
 * short-circuits to an empty result without touching Snowflake. DML returns the driver's affected
 * row count; DDL returns 0. Connections are per-request by design — see
 * {@link SnowflakeConnectionFactory}.
 */
class SnowflakeQueryExecutor {

    private final SnowflakeConnectionFactory connectionFactory;
    private final SnowflakeQueryParser parser;
    private final SnowflakeRowSecurityApplier rowSecurityApplier;
    private final SnowflakeResultMapper resultMapper;
    private final SnowflakeExceptionTranslator exceptionTranslator;
    private final EngineMessages messages;
    private final Clock clock;

    SnowflakeQueryExecutor(SnowflakeConnectionFactory connectionFactory,
                           SnowflakeQueryParser parser,
                           SnowflakeRowSecurityApplier rowSecurityApplier,
                           SnowflakeResultMapper resultMapper,
                           SnowflakeExceptionTranslator exceptionTranslator,
                           EngineMessages messages, Clock clock) {
        this.connectionFactory = connectionFactory;
        this.parser = parser;
        this.rowSecurityApplier = rowSecurityApplier;
        this.resultMapper = resultMapper;
        this.exceptionTranslator = exceptionTranslator;
        this.messages = messages;
        this.clock = clock;
    }

    QueryExecutionResult execute(QueryExecutionRequest request,
                                 DatasourceConnectionDescriptor descriptor, int maxRows,
                                 Duration timeout) {
        var start = clock.instant();
        var statement = parser.parseStatement(request.sql());
        var applied = rowSecurityApplier.apply(statement, request.rowSecurityPredicates());
        if (applied.denyAll()) {
            if (statement.kind().isRead()) {
                return new SelectExecutionResult(List.of(), List.of(), 0, false,
                        durationSince(start)).withRowSecurityPolicyIds(applied.appliedPolicyIds());
            }
            return new UpdateExecutionResult(0, durationSince(start), applied.appliedPolicyIds());
        }
        try (var connection = connectionFactory.open(descriptor);
             var prepared = connection.prepareStatement(applied.statement())) {
            bindParameters(prepared, applied.parameters());
            prepared.setQueryTimeout((int) Math.max(1, timeout.toSeconds()));
            if (statement.kind().isRead()) {
                prepared.setMaxRows(maxRows + 1);
                try (var resultSet = prepared.executeQuery()) {
                    var result = resultMapper.materialize(resultSet, maxRows, start, clock,
                            request.restrictedColumns(), request.columnMasks());
                    return applied.appliedPolicyIds().isEmpty()
                            ? result
                            : result.withRowSecurityPolicyIds(applied.appliedPolicyIds());
                }
            }
            if (statement.kind() == SnowflakeStatementKind.DDL) {
                prepared.execute();
                return new UpdateExecutionResult(0, durationSince(start),
                        applied.appliedPolicyIds());
            }
            long affected = prepared.executeUpdate();
            return new UpdateExecutionResult(affected, durationSince(start),
                    applied.appliedPolicyIds());
        } catch (SQLException ex) {
            throw exceptionTranslator.translate(ex, timeout);
        } catch (SnowflakeConfigException ex) {
            var message = messages.get(ex.messageKey(), ex.args());
            throw new QueryExecutionFailedException(message, message, null, 0, ex);
        }
    }

    SelectExecutionResult sampleTable(SampleTableRequest request,
                                      DatasourceConnectionDescriptor descriptor, int maxRows,
                                      Duration timeout) {
        // Sample = SELECT * FROM "schema"."table", funneled through the same parse + row-security
        // + masking pipeline as execute(). The names come from introspection (allow-listed);
        // double-quotes are doubled defensively.
        var qualified = quote(request.table());
        if (request.schema() != null && !request.schema().isBlank()) {
            qualified = quote(request.schema()) + "." + qualified;
        }
        var sql = "SELECT * FROM " + qualified;
        var execRequest = new QueryExecutionRequest(request.datasourceId(), sql, QueryType.SELECT,
                null, null, request.restrictedColumns(), request.columnMasks(),
                request.rowSecurityPredicates(), false, null);
        return (SelectExecutionResult) execute(execRequest, descriptor, maxRows, timeout);
    }

    private static void bindParameters(PreparedStatement prepared, List<Object> parameters)
            throws SQLException {
        for (int i = 0; i < parameters.size(); i++) {
            prepared.setObject(i + 1, parameters.get(i));
        }
    }

    private static String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    private Duration durationSince(Instant start) {
        return Duration.between(start, clock.instant());
    }
}
