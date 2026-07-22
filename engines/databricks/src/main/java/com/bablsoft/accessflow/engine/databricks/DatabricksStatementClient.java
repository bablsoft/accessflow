package com.bablsoft.accessflow.engine.databricks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.SequencedMap;

/**
 * Thin client for the Databricks SQL Statement Execution API
 * ({@code /api/2.0/sql/statements}), built on the JDK {@link HttpClient} — deliberately no vendor
 * SDK and no JDBC driver, so the shaded plugin stays a couple of megabytes. One statement runs as:
 * submit ({@code POST}, hybrid wait via {@code wait_timeout}/{@code on_wait_timeout=CONTINUE},
 * {@code format=JSON_ARRAY}, {@code disposition=INLINE}) → poll ({@code GET …/{id}}) while
 * {@code PENDING}/{@code RUNNING} at the configured interval → on the host deadline (measured with
 * the host clock) a best-effort cancel ({@code POST …/{id}/cancel}) and a timed-out
 * {@link DatabricksApiException}. A {@code SUCCEEDED} statement's inline result follows
 * {@code next_chunk_index} chunk links ({@code GET …/{id}/result/chunks/{n}}) until complete.
 * Terminal {@code FAILED}/{@code CANCELED}/{@code CLOSED} states and non-2xx HTTP responses raise
 * {@link DatabricksApiException} carrying the verbatim API error message. Row-security values ride
 * as typed named {@code parameters} — never concatenated into the statement text.
 */
class DatabricksStatementClient {

    private static final Logger log = LoggerFactory.getLogger(DatabricksStatementClient.class);
    private static final String STATEMENTS_PATH = "/api/2.0/sql/statements";
    private static final Duration MIN_REQUEST_TIMEOUT = Duration.ofSeconds(1);
    private static final Duration WAIT_RESPONSE_GRACE = Duration.ofSeconds(10);

    /** One manifest column: name + Databricks type name, ordered by manifest position. */
    record Column(String name, String typeName) {
    }

    /**
     * The materialized inline result of a {@code SUCCEEDED} statement: ordered columns, rows of
     * string-or-null values (the {@code JSON_ARRAY} wire format), and the manifest's own
     * truncation flag (set when the server-side {@code row_limit} cut the result).
     */
    record StatementResult(List<Column> columns, List<List<String>> rows, boolean truncated) {
    }

    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();
    private final DatabricksEngineSettings settings;
    private final Clock clock;

    DatabricksStatementClient(HttpClient http, DatabricksEngineSettings settings, Clock clock) {
        this.http = http;
        this.settings = settings;
        this.clock = clock;
    }

    /**
     * Runs one statement to completion within {@code timeout} (the host-computed statement
     * timeout) and returns its inline result.
     *
     * @param rowLimit server-side {@code row_limit} ({@code null} to omit — DML/DDL)
     */
    StatementResult execute(DatabricksEndpoint endpoint, String accessToken, String catalog,
                            String statement, SequencedMap<String, Object> parameters,
                            Integer rowLimit, Duration timeout) {
        var deadline = clock.instant().plus(timeout);
        var body = submitBody(endpoint, catalog, statement, parameters, rowLimit);
        var response = send(post(endpoint.baseUrl() + STATEMENTS_PATH, accessToken, body,
                deadline), endpoint, accessToken, null, deadline);
        var statementId = response.path("statement_id").asText(null);
        response = pollUntilTerminal(response, endpoint, accessToken, statementId, deadline);
        var state = response.path("status").path("state").asText("");
        if (!"SUCCEEDED".equals(state)) {
            throw terminalFailure(response, state);
        }
        return materialize(response, endpoint, accessToken, statementId, deadline);
    }

    // ---- request building --------------------------------------------------------------------

    private String submitBody(DatabricksEndpoint endpoint, String catalog, String statement,
                              SequencedMap<String, Object> parameters, Integer rowLimit) {
        ObjectNode body = mapper.createObjectNode();
        body.put("statement", statement);
        body.put("warehouse_id", endpoint.warehouseId());
        body.put("wait_timeout", settings.waitTimeoutValue());
        body.put("on_wait_timeout", "CONTINUE");
        body.put("format", "JSON_ARRAY");
        body.put("disposition", "INLINE");
        if (rowLimit != null) {
            body.put("row_limit", rowLimit.longValue());
        }
        if (catalog != null && !catalog.isBlank()) {
            body.put("catalog", catalog.strip());
        }
        if (parameters != null && !parameters.isEmpty()) {
            ArrayNode array = body.putArray("parameters");
            for (var entry : parameters.entrySet()) {
                ObjectNode parameter = array.addObject();
                parameter.put("name", entry.getKey());
                parameter.put("type", parameterType(entry.getValue()));
                if (entry.getValue() != null) {
                    parameter.put("value", String.valueOf(entry.getValue()));
                }
            }
        }
        return body.toString();
    }

    /** Maps a row-security value's Java class onto a Databricks SQL parameter type. */
    private static String parameterType(Object value) {
        return switch (value) {
            case Boolean ignored -> "BOOLEAN";
            case Integer ignored -> "BIGINT";
            case Long ignored -> "BIGINT";
            case Short ignored -> "BIGINT";
            case Byte ignored -> "BIGINT";
            case BigInteger ignored -> "BIGINT";
            case Double ignored -> "DOUBLE";
            case Float ignored -> "DOUBLE";
            case BigDecimal ignored -> "DOUBLE";
            case null, default -> "STRING";
        };
    }

    // ---- polling / lifecycle -------------------------------------------------------------------

    private JsonNode pollUntilTerminal(JsonNode response, DatabricksEndpoint endpoint,
                                       String accessToken, String statementId, Instant deadline) {
        var state = response.path("status").path("state").asText("");
        while ("PENDING".equals(state) || "RUNNING".equals(state)) {
            if (!clock.instant().isBefore(deadline)) {
                throw timedOut(endpoint, accessToken, statementId);
            }
            sleep(endpoint, accessToken, statementId);
            response = send(get(statementUrl(endpoint, statementId), accessToken, deadline),
                    endpoint, accessToken, statementId, deadline);
            state = response.path("status").path("state").asText("");
        }
        return response;
    }

    private void sleep(DatabricksEndpoint endpoint, String accessToken, String statementId) {
        try {
            Thread.sleep(settings.pollInterval().toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            cancelQuietly(endpoint, accessToken, statementId);
            throw new DatabricksApiException("Interrupted while awaiting statement completion", e);
        }
    }

    private DatabricksApiException timedOut(DatabricksEndpoint endpoint, String accessToken,
                                            String statementId) {
        cancelQuietly(endpoint, accessToken, statementId);
        return new DatabricksApiException("Statement execution deadline exceeded", null, 0, true);
    }

    /** Best-effort cancel — the host deadline has already been decided; failures are logged only. */
    private void cancelQuietly(DatabricksEndpoint endpoint, String accessToken,
                               String statementId) {
        if (statementId == null) {
            return;
        }
        try {
            var request = HttpRequest.newBuilder(
                            URI.create(statementUrl(endpoint, statementId) + "/cancel"))
                    .header("Authorization", "Bearer " + accessToken)
                    .timeout(WAIT_RESPONSE_GRACE)
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();
            http.send(request, HttpResponse.BodyHandlers.discarding());
        } catch (IOException e) {
            log.warn("Best-effort cancel of Databricks statement {} failed: {}", statementId,
                    e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static DatabricksApiException terminalFailure(JsonNode response, String state) {
        var error = response.path("status").path("error");
        var message = error.path("message").asText(null);
        if (message == null || message.isBlank()) {
            message = "Statement finished in state " + state;
        }
        return new DatabricksApiException(message, error.path("error_code").asText(null), 200,
                false);
    }

    // ---- result materialization ------------------------------------------------------------------

    private StatementResult materialize(JsonNode response, DatabricksEndpoint endpoint,
                                        String accessToken, String statementId, Instant deadline) {
        var manifest = response.path("manifest");
        var columns = new ArrayList<Column>();
        for (var column : manifest.path("schema").path("columns")) {
            columns.add(new Column(column.path("name").asText(""),
                    column.path("type_name").asText("")));
        }
        var rows = new ArrayList<List<String>>();
        var result = response.path("result");
        appendRows(result, rows);
        var next = result.path("next_chunk_index");
        while (next.isNumber()) {
            var chunk = send(get(statementUrl(endpoint, statementId) + "/result/chunks/"
                    + next.asInt(), accessToken, deadline), endpoint, accessToken, statementId,
                    deadline);
            appendRows(chunk, rows);
            next = chunk.path("next_chunk_index");
        }
        return new StatementResult(List.copyOf(columns), rows,
                manifest.path("truncated").asBoolean(false));
    }

    private static void appendRows(JsonNode container, List<List<String>> rows) {
        for (var row : container.path("data_array")) {
            var values = new ArrayList<String>(row.size());
            for (var value : row) {
                values.add(value.isNull() ? null : value.asText());
            }
            rows.add(values);
        }
    }

    // ---- HTTP plumbing -----------------------------------------------------------------------------

    private HttpRequest post(String url, String accessToken, String body, Instant deadline) {
        return HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/json")
                .timeout(requestTimeout(deadline, settings.waitTimeout().plus(WAIT_RESPONSE_GRACE)))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
    }

    private HttpRequest get(String url, String accessToken, Instant deadline) {
        return HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", "Bearer " + accessToken)
                .timeout(requestTimeout(deadline, WAIT_RESPONSE_GRACE))
                .GET()
                .build();
    }

    /** Per-request timeout: the remaining deadline budget, floored so a request can still run. */
    private Duration requestTimeout(Instant deadline, Duration cap) {
        var remaining = Duration.between(clock.instant(), deadline);
        if (remaining.compareTo(MIN_REQUEST_TIMEOUT) < 0) {
            remaining = MIN_REQUEST_TIMEOUT;
        }
        return remaining.compareTo(cap) > 0 ? cap : remaining;
    }

    private JsonNode send(HttpRequest request, DatabricksEndpoint endpoint, String accessToken,
                          String statementId, Instant deadline) {
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException e) {
            if (!clock.instant().isBefore(deadline)) {
                throw timedOut(endpoint, accessToken, statementId);
            }
            throw new DatabricksApiException("Databricks API request timed out: "
                    + e.getMessage(), e);
        } catch (IOException e) {
            throw new DatabricksApiException("Databricks API request failed: "
                    + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            cancelQuietly(endpoint, accessToken, statementId);
            throw new DatabricksApiException("Interrupted during Databricks API request", e);
        }
        if (response.statusCode() / 100 != 2) {
            throw httpFailure(response);
        }
        try {
            return mapper.readTree(response.body());
        } catch (IOException e) {
            throw new DatabricksApiException("Unparseable Databricks API response", e);
        }
    }

    private DatabricksApiException httpFailure(HttpResponse<String> response) {
        String message = null;
        String errorCode = null;
        try {
            var body = mapper.readTree(response.body());
            message = body.path("message").asText(null);
            errorCode = body.path("error_code").asText(null);
        } catch (IOException e) {
            log.debug("Non-JSON Databricks error body (HTTP {})", response.statusCode());
        }
        if (message == null || message.isBlank()) {
            message = "Databricks API returned HTTP " + response.statusCode();
        }
        return new DatabricksApiException(message, errorCode, response.statusCode(), false);
    }

    private static String statementUrl(DatabricksEndpoint endpoint, String statementId) {
        return endpoint.baseUrl() + STATEMENTS_PATH + "/" + statementId;
    }
}
