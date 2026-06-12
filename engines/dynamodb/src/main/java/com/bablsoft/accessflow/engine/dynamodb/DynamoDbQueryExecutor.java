package com.bablsoft.accessflow.engine.dynamodb;

import com.bablsoft.accessflow.core.api.DatasourceConnectionDescriptor;
import com.bablsoft.accessflow.core.api.EngineMessages;
import com.bablsoft.accessflow.core.api.QueryExecutionRequest;
import com.bablsoft.accessflow.core.api.QueryExecutionResult;
import com.bablsoft.accessflow.core.api.SelectExecutionResult;
import com.bablsoft.accessflow.core.api.UpdateExecutionResult;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ExecuteStatementRequest;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Executes a DynamoDB query for a {@code DYNAMODB} datasource — the key-value analogue of the host's
 * JDBC execution path. A submission is either PartiQL ({@code SELECT}/{@code INSERT}/{@code UPDATE}/
 * {@code DELETE}) or a JSON table-management command ({@link DynamoDbDdlCommand}). PartiQL flow:
 * re-parse, apply row-security predicates (rewritten PartiQL + positional {@code ?} parameters,
 * never concatenated), then run {@code ExecuteStatement}. SELECTs page through {@code NextToken}
 * capped at {@code maxRows + 1} to detect truncation, then map through {@link DynamoDbResultMapper}
 * (which applies column masking). A deny-all row-security result short-circuits to an empty result
 * without touching DynamoDB. DML returns 1 affected row (0 on deny-all); DDL runs the control-plane
 * call and returns 0. The host-computed statement timeout is applied per request.
 */
class DynamoDbQueryExecutor {

    private final DynamoDbClientManager clientManager;
    private final PartiQlQueryParser parser;
    private final DynamoDbRowSecurityApplier rowSecurityApplier;
    private final DynamoDbResultMapper resultMapper;
    private final DynamoDbExceptionTranslator exceptionTranslator;
    private final EngineMessages messages;
    private final Clock clock;

    DynamoDbQueryExecutor(DynamoDbClientManager clientManager, PartiQlQueryParser parser,
                          DynamoDbRowSecurityApplier rowSecurityApplier,
                          DynamoDbResultMapper resultMapper,
                          DynamoDbExceptionTranslator exceptionTranslator,
                          EngineMessages messages, Clock clock) {
        this.clientManager = clientManager;
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
        var client = clientManager.client(descriptor);
        if (PartiQlQueryParser.isJsonCommand(request.sql())) {
            return executeDdl(client, request.sql(), start, timeout);
        }
        var statement = parser.parseStatement(request.sql());
        var applied = rowSecurityApplier.apply(statement, request.rowSecurityPredicates());
        try {
            if (statement.kind().isRead()) {
                if (applied.denyAll()) {
                    return new SelectExecutionResult(List.of(), List.of(), 0, false,
                            durationSince(start)).withRowSecurityPolicyIds(applied.appliedPolicyIds());
                }
                var items = executeRead(client, applied, maxRows, timeout);
                var result = resultMapper.materialize(items, maxRows, durationSince(start),
                        request.restrictedColumns(), request.columnMasks());
                return applied.appliedPolicyIds().isEmpty()
                        ? result
                        : result.withRowSecurityPolicyIds(applied.appliedPolicyIds());
            }
            if (applied.denyAll()) {
                return new UpdateExecutionResult(0, durationSince(start), applied.appliedPolicyIds());
            }
            executeWrite(client, applied, timeout);
            return new UpdateExecutionResult(1, durationSince(start), applied.appliedPolicyIds());
        } catch (SdkException ex) {
            throw exceptionTranslator.translate(ex, timeout);
        }
    }

    private QueryExecutionResult executeDdl(DynamoDbClient client, String sql, Instant start,
                                            Duration timeout) {
        var command = DynamoDbDdlCommand.parse(sql, messages);
        try {
            switch (command.operation()) {
                case CREATE_TABLE -> client.createTable(command.toCreateTable());
                case DELETE_TABLE -> client.deleteTable(command.toDeleteTable());
                case UPDATE_TABLE -> client.updateTable(command.toUpdateTable());
            }
            return new UpdateExecutionResult(0, durationSince(start));
        } catch (SdkException ex) {
            throw exceptionTranslator.translate(ex, timeout);
        }
    }

    /** Collect up to {@code maxRows + 1} items (the truncation sentinel) across NextToken pages. */
    private List<Map<String, AttributeValue>> executeRead(DynamoDbClient client,
                                                          DynamoDbRowSecurityApplier.Applied applied,
                                                          int maxRows, Duration timeout) {
        int limit = maxRows + 1;
        var items = new ArrayList<Map<String, AttributeValue>>();
        String nextToken = null;
        do {
            var response = client.executeStatement(
                    readRequest(applied, limit, nextToken, timeout));
            items.addAll(response.items());
            nextToken = response.nextToken();
        } while (nextToken != null && items.size() < limit);
        return items;
    }

    private void executeWrite(DynamoDbClient client, DynamoDbRowSecurityApplier.Applied applied,
                              Duration timeout) {
        var builder = ExecuteStatementRequest.builder()
                .statement(applied.statement())
                .overrideConfiguration(o -> o.apiCallTimeout(timeout));
        var parameters = toParameters(applied.parameters());
        if (!parameters.isEmpty()) {
            builder.parameters(parameters);
        }
        client.executeStatement(builder.build());
    }

    private static ExecuteStatementRequest readRequest(DynamoDbRowSecurityApplier.Applied applied,
                                                       int limit, String nextToken, Duration timeout) {
        var builder = ExecuteStatementRequest.builder()
                .statement(applied.statement())
                .limit(limit)
                .overrideConfiguration(o -> o.apiCallTimeout(timeout));
        var parameters = toParameters(applied.parameters());
        if (!parameters.isEmpty()) {
            builder.parameters(parameters);
        }
        if (nextToken != null) {
            builder.nextToken(nextToken);
        }
        return builder.build();
    }

    private static List<AttributeValue> toParameters(List<Object> values) {
        var parameters = new ArrayList<AttributeValue>(values.size());
        for (var value : values) {
            parameters.add(toAttributeValue(value));
        }
        return parameters;
    }

    private static AttributeValue toAttributeValue(Object value) {
        return switch (value) {
            case null -> AttributeValue.fromNul(true);
            case String s -> AttributeValue.fromS(s);
            case Boolean b -> AttributeValue.fromBool(b);
            case Number n -> AttributeValue.fromN(n.toString());
            default -> AttributeValue.fromS(String.valueOf(value));
        };
    }

    private Duration durationSince(Instant start) {
        return Duration.between(start, clock.instant());
    }
}
