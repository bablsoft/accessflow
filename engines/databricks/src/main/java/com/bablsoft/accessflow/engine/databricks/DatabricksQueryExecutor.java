package com.bablsoft.accessflow.engine.databricks;

import com.bablsoft.accessflow.core.api.CredentialDecryptor;
import com.bablsoft.accessflow.core.api.DatasourceConnectionDescriptor;
import com.bablsoft.accessflow.core.api.EngineMessages;
import com.bablsoft.accessflow.core.api.QueryExecutionFailedException;
import com.bablsoft.accessflow.core.api.QueryExecutionRequest;
import com.bablsoft.accessflow.core.api.QueryExecutionResult;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.SampleTableRequest;
import com.bablsoft.accessflow.core.api.SelectExecutionResult;
import com.bablsoft.accessflow.core.api.UpdateExecutionResult;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Executes a Databricks SQL query for a {@code DATABRICKS} datasource — the warehouse analogue of
 * the host's JDBC execution path. Flow: re-parse, apply row-security predicates (rewritten SQL +
 * named {@code :afp_n} parameters, never concatenated), then run the statement through the
 * Statement Execution API. A deny-all row-security result short-circuits to an empty result
 * <strong>without any HTTP call</strong>. SELECTs are submitted with {@code row_limit = maxRows +
 * 1} (the truncation sentinel) and mapped through {@link DatabricksResultMapper} (which applies
 * column masking); DML reads the {@code num_affected_rows} value Databricks returns as a one-row
 * result (0 when the shape is absent); DDL returns 0 affected rows. The host-computed statement
 * timeout is the submit→poll deadline; on expiry the statement is cancelled best-effort.
 */
class DatabricksQueryExecutor {

    private final DatabricksStatementClient client;
    private final DatabricksQueryParser parser;
    private final DatabricksRowSecurityApplier rowSecurityApplier;
    private final DatabricksResultMapper resultMapper;
    private final DatabricksExceptionTranslator exceptionTranslator;
    private final CredentialDecryptor credentials;
    private final EngineMessages messages;
    private final Clock clock;

    DatabricksQueryExecutor(DatabricksStatementClient client, DatabricksQueryParser parser,
                            DatabricksRowSecurityApplier rowSecurityApplier,
                            DatabricksResultMapper resultMapper,
                            DatabricksExceptionTranslator exceptionTranslator,
                            CredentialDecryptor credentials, EngineMessages messages,
                            Clock clock) {
        this.client = client;
        this.parser = parser;
        this.rowSecurityApplier = rowSecurityApplier;
        this.resultMapper = resultMapper;
        this.exceptionTranslator = exceptionTranslator;
        this.credentials = credentials;
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
            // Fail closed with ZERO HTTP calls — an unsatisfiable predicate returns nothing.
            if (statement.kind().isRead()) {
                return new SelectExecutionResult(List.of(), List.of(), 0, false,
                        durationSince(start)).withRowSecurityPolicyIds(applied.appliedPolicyIds());
            }
            return new UpdateExecutionResult(0, durationSince(start), applied.appliedPolicyIds());
        }
        var endpoint = resolveEndpoint(descriptor);
        var accessToken = credentials.decrypt(descriptor.passwordEncrypted());
        Integer rowLimit = statement.kind().isRead() ? maxRows + 1 : null;
        DatabricksStatementClient.StatementResult result;
        try {
            result = client.execute(endpoint, accessToken, descriptor.databaseName(),
                    applied.statement(), applied.parameters(), rowLimit, timeout);
        } catch (DatabricksApiException ex) {
            throw exceptionTranslator.translate(ex, timeout);
        }
        if (statement.kind().isRead()) {
            var mapped = resultMapper.materialize(result, maxRows, durationSince(start),
                    request.restrictedColumns(), request.columnMasks());
            return applied.appliedPolicyIds().isEmpty()
                    ? mapped
                    : mapped.withRowSecurityPolicyIds(applied.appliedPolicyIds());
        }
        if (statement.kind() == DatabricksStatementKind.DDL) {
            return new UpdateExecutionResult(0, durationSince(start));
        }
        return new UpdateExecutionResult(resultMapper.affectedRows(result), durationSince(start),
                applied.appliedPolicyIds());
    }

    SelectExecutionResult sampleTable(SampleTableRequest request,
                                      DatasourceConnectionDescriptor descriptor, int maxRows,
                                      Duration timeout) {
        // Sample = SELECT * FROM `schema`.`table`, funneled through the same parse + row-security
        // + masking pipeline as execute(). The names come from introspection (allow-listed);
        // embedded backticks are stripped defensively.
        var target = request.schema() == null || request.schema().isBlank()
                ? backtick(request.table())
                : backtick(request.schema()) + "." + backtick(request.table());
        var sql = "SELECT * FROM " + target;
        var execRequest = new QueryExecutionRequest(request.datasourceId(), sql, QueryType.SELECT,
                null, null, request.restrictedColumns(), request.columnMasks(),
                request.rowSecurityPredicates(), false, null);
        return (SelectExecutionResult) execute(execRequest, descriptor, maxRows, timeout);
    }

    private DatabricksEndpoint resolveEndpoint(DatasourceConnectionDescriptor descriptor) {
        try {
            return DatabricksEndpoint.resolve(descriptor, messages);
        } catch (IllegalArgumentException e) {
            throw new QueryExecutionFailedException(
                    messages.get("error.query_execution_failed"), e.getMessage(), null, 0, e);
        }
    }

    private static String backtick(String name) {
        return "`" + name.replace("`", "") + "`";
    }

    private Duration durationSince(Instant start) {
        return Duration.between(start, clock.instant());
    }
}
