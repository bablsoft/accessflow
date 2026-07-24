package com.bablsoft.accessflow.engine.elasticsearch;

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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Executes an approved query for an {@code ELASTICSEARCH} / {@code OPENSEARCH} datasource — the
 * search-engine analogue of the host's JDBC execution path. Re-parses the submitted envelope,
 * applies row-security predicates (a {@code bool.filter} wrapping the user query, never concatenated
 * values), dispatches to the matching REST endpoint, and maps the response. Reads page through the
 * cluster capped at {@code maxRows + 1} to detect truncation; the host-computed statement timeout is
 * applied as the {@code ?timeout=} request parameter (and the client socket timeout). A search /
 * by-query {@code timed_out:true} (HTTP 200) and a bulk {@code errors:true} are translated to
 * execution exceptions so a partial result never masquerades as success.
 */
class EsQueryExecutor {

    private static final String JSON = "application/json";
    private static final String NDJSON = "application/x-ndjson";
    /** Elasticsearch / OpenSearch default {@code index.max_result_window}; {@code from + size} cap. */
    private static final int MAX_RESULT_WINDOW = 10_000;

    private final SearchClientManager clientManager;
    private final EsQueryParser parser;
    private final EsRowSecurityApplier rowSecurityApplier;
    private final EsResultMapper resultMapper;
    private final EsExceptionTranslator exceptionTranslator;
    private final Clock clock;

    EsQueryExecutor(SearchClientManager clientManager, EsQueryParser parser,
                    EsRowSecurityApplier rowSecurityApplier, EsResultMapper resultMapper,
                    EsExceptionTranslator exceptionTranslator, Clock clock) {
        this.clientManager = clientManager;
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
        var command = parser.parseCommand(request.sql());
        var applied = rowSecurityApplier.apply(command, request.rowSecurityPredicates());
        var cmd = applied.command();
        var policyIds = applied.appliedPolicyIds();
        var transport = clientManager.transport(descriptor);
        try {
            return switch (cmd.operation()) {
                case SEARCH -> search(transport, cmd, request, maxRows, timeout, start, policyIds);
                case COUNT -> count(transport, cmd, timeout, start, policyIds);
                case INDEX -> index(transport, cmd, start);
                case BULK -> bulk(transport, cmd, start);
                case UPDATE_BY_QUERY ->
                        byQuery(transport, cmd, "_update_by_query", "updated", timeout, start, policyIds);
                case DELETE_BY_QUERY ->
                        byQuery(transport, cmd, "_delete_by_query", "deleted", timeout, start, policyIds);
                case CREATE_INDEX, PUT_MAPPING, DELETE_INDEX -> ddl(transport, cmd, start);
            };
        } catch (SearchTransportException ex) {
            throw exceptionTranslator.translate(ex, timeout);
        }
    }

    SelectExecutionResult sampleTable(SampleTableRequest request,
                                      DatasourceConnectionDescriptor descriptor, int maxRows,
                                      Duration timeout) {
        // Sample = match_all search over the index (no query ⇒ match_all default), funneled through
        // the same parse + bool.filter row-security + masking pipeline as execute().
        var envelope = EsJson.object();
        envelope.put("search", request.table());
        var execRequest = new QueryExecutionRequest(request.datasourceId(), EsJson.write(envelope),
                QueryType.SELECT, null, null, request.restrictedColumns(), request.columnMasks(),
                request.rowSecurityPredicates(), false, null);
        return (SelectExecutionResult) execute(execRequest, descriptor, maxRows, timeout);
    }

    QueryDryRunResult dryRun(String engineId, QueryExecutionRequest request,
                             DatasourceConnectionDescriptor descriptor, Duration timeout) {
        var start = clock.instant();
        var command = parser.parseCommand(request.sql());
        // Only query-bearing operations can be explained without executing. Index/bulk/DDL cannot.
        if (!command.operation().isRead() && command.operation() != EsOperation.UPDATE_BY_QUERY
                && command.operation() != EsOperation.DELETE_BY_QUERY) {
            return QueryDryRunResult.unsupported(engineId);
        }
        var applied = rowSecurityApplier.apply(command, request.rowSecurityPredicates());
        var cmd = applied.command();
        var transport = clientManager.transport(descriptor);
        try {
            var body = EsJson.object();
            body.set("query", cmd.query() != null ? cmd.query() : EsJson.matchAll());
            // _validate/query?explain plans/validates the query without executing it.
            var raw = transport.perform("POST", path(cmd.index(), "_validate/query"),
                    Map.of("explain", "true"), EsJson.write(body), JSON);
            var response = EsJson.parse(raw);
            return QueryDryRunResult.of(engineId, cmd.operation().queryType(), null,
                    EsPlanMapper.toPlan(response, cmd.index()), raw, applied.appliedPolicyIds(),
                    durationSince(start));
        } catch (SearchTransportException ex) {
            throw exceptionTranslator.translate(ex, timeout);
        }
    }

    QueryAffectedRowsResult countAffectedRows(String engineId, QueryExecutionRequest request,
                                              DatasourceConnectionDescriptor descriptor,
                                              Duration timeout) {
        var start = clock.instant();
        EsCommand cmd;
        try {
            var command = parser.parseCommand(request.sql());
            // Only the by-query mutations have a countable pre-image; everything else degrades.
            if (command.operation() != EsOperation.UPDATE_BY_QUERY
                    && command.operation() != EsOperation.DELETE_BY_QUERY) {
                return QueryAffectedRowsResult.unsupported(engineId);
            }
            cmd = rowSecurityApplier.apply(command, request.rowSecurityPredicates()).command();
        } catch (InvalidSqlException | UnrewritableRowSecurityException ex) {
            // A preflight count never throws for shape reasons — fail closed to "unsupported".
            return QueryAffectedRowsResult.unsupported(engineId, ex.getMessage());
        }
        var transport = clientManager.transport(descriptor);
        try {
            var body = EsJson.object();
            body.set("query", cmd.query() != null ? cmd.query() : EsJson.matchAll());
            // Non-mutating _count of the governed (bool.filter-wrapped) query — the same endpoint
            // the COUNT read uses; _count does not accept a ?timeout= query parameter.
            var response = EsJson.parse(transport.perform("POST", path(cmd.index(), "_count"),
                    Map.of(), EsJson.write(body), JSON));
            return QueryAffectedRowsResult.of(engineId, longField(response, "count"),
                    durationSince(start));
        } catch (SearchTransportException ex) {
            throw exceptionTranslator.translate(ex, timeout);
        }
    }

    private QueryExecutionResult search(SearchTransport transport, EsCommand cmd,
                                        QueryExecutionRequest request, int maxRows, Duration timeout,
                                        Instant start, Set<UUID> policyIds) {
        int effectiveSize = cmd.size() != null ? Math.min(cmd.size(), maxRows) : maxRows;
        int from = cmd.from() != null ? cmd.from() : 0;
        // Fetch one past the cap to detect truncation, but never let from + size exceed the
        // index.max_result_window (10000) — ES/OpenSearch reject that with a 400.
        int requestSize = Math.min(effectiveSize + 1, Math.max(0, MAX_RESULT_WINDOW - from));
        var body = EsJson.object();
        body.set("query", cmd.query() != null ? cmd.query() : EsJson.matchAll());
        body.put("size", requestSize);
        if (cmd.from() != null) {
            body.put("from", from);
        }
        if (cmd.sort() != null) {
            body.set("sort", cmd.sort());
        }
        if (cmd.source() != null) {
            body.set("_source", cmd.source());
        }
        var response = EsJson.parse(transport.perform("POST", path(cmd.index(), "_search"),
                timeoutParams(timeout), EsJson.write(body), JSON));
        if (boolField(response, "timed_out")) {
            throw exceptionTranslator.timedOut(timeout);
        }
        var hits = response.path("hits").path("hits");
        var result = resultMapper.materializeSearch(hits, cmd.sort() != null, effectiveSize,
                durationSince(start), request.restrictedColumns(), request.columnMasks());
        return policyIds.isEmpty() ? result : result.withRowSecurityPolicyIds(policyIds);
    }

    private QueryExecutionResult count(SearchTransport transport, EsCommand cmd, Duration timeout,
                                       Instant start, Set<UUID> policyIds) {
        var body = EsJson.object();
        body.set("query", cmd.query() != null ? cmd.query() : EsJson.matchAll());
        // The _count API does not accept a ?timeout= query parameter (unlike _search).
        var response = EsJson.parse(transport.perform("POST", path(cmd.index(), "_count"),
                Map.of(), EsJson.write(body), JSON));
        var result = resultMapper.materializeCount(longField(response, "count"), durationSince(start));
        return policyIds.isEmpty() ? result : result.withRowSecurityPolicyIds(policyIds);
    }

    private QueryExecutionResult index(SearchTransport transport, EsCommand cmd, Instant start) {
        var method = cmd.docId() != null ? "PUT" : "POST";
        var path = cmd.docId() != null
                ? "/" + cmd.index() + "/_doc/" + cmd.docId()
                : "/" + cmd.index() + "/_doc";
        transport.perform(method, path, refreshParams(), EsJson.write(cmd.document()), JSON);
        return new UpdateExecutionResult(1, durationSince(start));
    }

    private QueryExecutionResult bulk(SearchTransport transport, EsCommand cmd, Instant start) {
        var ndjson = new StringBuilder();
        for (var item : cmd.bulkItems()) {
            var meta = EsJson.object();
            if (item.id() != null) {
                meta.put("_id", item.id());
            }
            var action = EsJson.object();
            action.set("index", meta);
            ndjson.append(EsJson.write(action)).append('\n');
            ndjson.append(EsJson.write(item.document())).append('\n');
        }
        var response = EsJson.parse(transport.perform("POST", path(cmd.index(), "_bulk"),
                refreshParams(), ndjson.toString(), NDJSON));
        if (boolField(response, "errors")) {
            throw exceptionTranslator.bulkFailed(firstBulkError(response));
        }
        long affected = response.path("items").isArray()
                ? response.path("items").size() : cmd.bulkItems().size();
        return new UpdateExecutionResult(affected, durationSince(start));
    }

    private QueryExecutionResult byQuery(SearchTransport transport, EsCommand cmd, String endpoint,
                                         String countField, Duration timeout, Instant start,
                                         Set<UUID> policyIds) {
        var body = EsJson.object();
        body.set("query", cmd.query() != null ? cmd.query() : EsJson.matchAll());
        var params = new LinkedHashMap<String, String>();
        params.put("conflicts", "proceed");
        params.put("refresh", "true");
        params.put("timeout", timeout.toMillis() + "ms");
        var response = EsJson.parse(transport.perform("POST", path(cmd.index(), endpoint),
                params, EsJson.write(body), JSON));
        if (boolField(response, "timed_out")) {
            throw exceptionTranslator.timedOut(timeout);
        }
        return new UpdateExecutionResult(longField(response, countField), durationSince(start),
                policyIds);
    }

    private QueryExecutionResult ddl(SearchTransport transport, EsCommand cmd, Instant start) {
        switch (cmd.operation()) {
            case CREATE_INDEX -> {
                var body = EsJson.object();
                if (cmd.mappings() != null) {
                    body.set("mappings", cmd.mappings());
                }
                if (cmd.settings() != null) {
                    body.set("settings", cmd.settings());
                }
                transport.perform("PUT", "/" + cmd.index(), Map.of(),
                        body.isEmpty() ? null : EsJson.write(body), JSON);
            }
            case PUT_MAPPING -> {
                var body = EsJson.object();
                body.set("properties", cmd.properties());
                transport.perform("PUT", path(cmd.index(), "_mapping"), Map.of(),
                        EsJson.write(body), JSON);
            }
            case DELETE_INDEX -> transport.perform("DELETE", "/" + cmd.index(), Map.of(), null, JSON);
            default -> throw new IllegalStateException("Not a DDL operation: " + cmd.operation());
        }
        return new UpdateExecutionResult(0, durationSince(start));
    }

    private static String path(String index, String endpoint) {
        return "/" + index + "/" + endpoint;
    }

    private static Map<String, String> timeoutParams(Duration timeout) {
        return Map.of("timeout", timeout.toMillis() + "ms");
    }

    private static Map<String, String> refreshParams() {
        return Map.of("refresh", "true");
    }

    private static boolean boolField(JsonNode node, String field) {
        var value = node.path(field);
        return value.isBoolean() && value.booleanValue();
    }

    private static long longField(JsonNode node, String field) {
        var value = node.path(field);
        return value.isNumber() ? value.longValue() : 0L;
    }

    private static String firstBulkError(JsonNode response) {
        for (var item : response.path("items")) {
            var indexResult = item.path("index");
            var error = indexResult.path("error");
            if (!error.isMissingNode() && !error.isNull()) {
                return EsJson.write(error);
            }
        }
        return "bulk request failed";
    }

    private Duration durationSince(Instant start) {
        return Duration.between(start, clock.instant());
    }
}
