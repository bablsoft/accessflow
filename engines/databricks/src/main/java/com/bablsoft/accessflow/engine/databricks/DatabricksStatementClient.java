package com.bablsoft.accessflow.engine.databricks;

import com.bablsoft.accessflow.engine.databricks.DatabricksEngineSettings.ResultDisposition;
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
import java.util.List;
import java.util.SequencedMap;

/**
 * Thin client for the Databricks SQL Statement Execution API
 * ({@code /api/2.0/sql/statements}), built on the JDK {@link HttpClient} — deliberately no vendor
 * SDK and no JDBC driver, so the shaded plugin stays a couple of megabytes. One statement runs as:
 * submit ({@code POST}, hybrid wait via {@code wait_timeout}/{@code on_wait_timeout=CONTINUE},
 * {@code format=JSON_ARRAY}) → poll ({@code GET …/{id}}) while {@code PENDING}/{@code RUNNING} at
 * the configured interval → on the host deadline (measured with the host clock) a best-effort
 * cancel ({@code POST …/{id}/cancel}) and a timed-out {@link DatabricksApiException}. A
 * {@code SUCCEEDED} statement's result is materialized by {@link DatabricksResultReader}, following
 * {@code next_chunk_index} chunk links ({@code GET …/{id}/result/chunks/{n}}) until complete.
 * Terminal {@code FAILED}/{@code CANCELED}/{@code CLOSED} states and non-2xx HTTP responses raise
 * {@link DatabricksApiException} carrying the verbatim API error message. Row-security values ride
 * as typed named {@code parameters} — never concatenated into the statement text.
 *
 * <p><strong>Disposition (AF-633).</strong> {@code INLINE} results are capped at roughly 25 MiB by
 * the API. Under the default {@code result-disposition=auto} the client submits {@code INLINE} and,
 * when {@link DatabricksInlineLimitDetector} sees that ceiling hit, re-submits the statement
 * <em>once</em> with {@code disposition=EXTERNAL_LINKS} under the <em>same</em> deadline — only for
 * a {@link StatementRequest#sideEffectFree()} statement, so a DML/DDL statement is never executed
 * twice. If the fallback itself fails, the <em>original</em> inline failure is what surfaces, which
 * is what lets the detector be generous: a false positive costs one wasted re-submission and can
 * never make the reported error worse than it is today.</p>
 */
class DatabricksStatementClient {

    private static final Logger log = LoggerFactory.getLogger(DatabricksStatementClient.class);
    private static final String STATEMENTS_PATH = "/api/2.0/sql/statements";
    private static final Duration MIN_REQUEST_TIMEOUT = Duration.ofSeconds(1);
    private static final Duration WAIT_RESPONSE_GRACE = Duration.ofSeconds(10);

    /** One manifest column: name + Databricks type name, ordered by manifest position. */
    record Column(String name, String typeName) {
    }

    /** Why a result stopped short of everything the statement produced. */
    enum Truncation {
        NONE, ROW_LIMIT, BYTE_LIMIT
    }

    /**
     * The materialized result of a {@code SUCCEEDED} statement: ordered columns, rows of
     * string-or-null values (the {@code JSON_ARRAY} wire format), and why it was cut short — the
     * server-side {@code row_limit} / manifest truncation flag, or the engine's byte backstop.
     */
    record StatementResult(List<Column> columns, List<List<String>> rows, Truncation truncation) {

        boolean truncated() {
            return truncation != Truncation.NONE;
        }
    }

    /**
     * One statement to run. The endpoint and the access token are deliberately <em>not</em> fields
     * here: a record generates a {@code toString()}, and one holding the workspace PAT is a leak
     * waiting for its first {@code log.debug("{}", request)}.
     *
     * @param rowLimit       server-side {@code row_limit} ({@code null} to omit — DML/DDL and the
     *                       unbounded introspection reads)
     * @param sideEffectFree whether re-running this statement is harmless, which is what makes it
     *                       eligible for the {@code EXTERNAL_LINKS} fallback
     */
    record StatementRequest(String catalog, String statement,
                            SequencedMap<String, Object> parameters, Integer rowLimit,
                            Duration timeout, boolean sideEffectFree) {

        static StatementRequest read(String catalog, String statement,
                                     SequencedMap<String, Object> parameters, Integer rowLimit,
                                     Duration timeout) {
            return new StatementRequest(catalog, statement, parameters, rowLimit, timeout, true);
        }

        static StatementRequest write(String catalog, String statement,
                                      SequencedMap<String, Object> parameters, Duration timeout) {
            return new StatementRequest(catalog, statement, parameters, null, timeout, false);
        }
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
     * Runs one statement to completion within the request's timeout (the host-computed statement
     * timeout) and returns its result, falling back to {@code EXTERNAL_LINKS} when the inline
     * ceiling is hit and the statement is safe to re-run.
     */
    StatementResult execute(DatabricksEndpoint endpoint, String accessToken,
                            StatementRequest request) {
        var deadline = clock.instant().plus(request.timeout());
        if (settings.resultDisposition() == ResultDisposition.EXTERNAL_LINKS) {
            return run(endpoint, accessToken, request, ResultDisposition.EXTERNAL_LINKS, deadline);
        }
        DatabricksApiException inlineFailure;
        try {
            var result = run(endpoint, accessToken, request, ResultDisposition.INLINE, deadline);
            if (!fallbackAllowed(request) || !DatabricksInlineLimitDetector.sizeTruncatedInline(
                    result.truncated(), request.rowLimit(), result.rows().size())) {
                return result;
            }
            inlineFailure = null;
        } catch (DatabricksApiException e) {
            if (!fallbackAllowed(request)
                    || !DatabricksInlineLimitDetector.inlineLimitExceeded(e)) {
                throw e;
            }
            inlineFailure = e;
        }
        return fallBack(endpoint, accessToken, request, deadline, inlineFailure);
    }

    private boolean fallbackAllowed(StatementRequest request) {
        return request.sideEffectFree() && settings.resultDisposition() == ResultDisposition.AUTO;
    }

    /**
     * The one-shot {@code EXTERNAL_LINKS} retry. When it fails, the inline failure that triggered
     * it is what the caller sees — it is the actionable one, and it keeps a false-positive
     * detection invisible.
     */
    private StatementResult fallBack(DatabricksEndpoint endpoint, String accessToken,
                                     StatementRequest request, Instant deadline,
                                     DatabricksApiException inlineFailure) {
        log.info("Databricks inline result limit reached; retrying with EXTERNAL_LINKS");
        try {
            return run(endpoint, accessToken, request, ResultDisposition.EXTERNAL_LINKS, deadline);
        } catch (DatabricksApiException e) {
            if (inlineFailure == null) {
                throw e;
            }
            log.warn("Databricks EXTERNAL_LINKS fallback failed ({}); surfacing the inline error",
                    e.getMessage());
            throw inlineFailure;
        }
    }

    private StatementResult run(DatabricksEndpoint endpoint, String accessToken,
                                StatementRequest request, ResultDisposition disposition,
                                Instant deadline) {
        var body = submitBody(endpoint, request, disposition);
        var response = send(post(endpoint.baseUrl() + STATEMENTS_PATH, accessToken, body,
                deadline), endpoint, accessToken, null, deadline);
        var statementId = response.path("statement_id").asText(null);
        response = pollUntilTerminal(response, endpoint, accessToken, statementId, deadline);
        var state = response.path("status").path("state").asText("");
        if (!"SUCCEEDED".equals(state)) {
            throw terminalFailure(response, state);
        }
        return new DatabricksResultReader(settings.maxResultBytes()).read(response,
                request.rowLimit(),
                chunkIndex -> send(get(statementUrl(endpoint, statementId) + "/result/chunks/"
                        + chunkIndex, accessToken, deadline), endpoint, accessToken, statementId,
                        deadline),
                new DatabricksExternalLinkReader(http, endpoint, clock, deadline));
    }

    // ---- request building --------------------------------------------------------------------

    private String submitBody(DatabricksEndpoint endpoint, StatementRequest request,
                              ResultDisposition disposition) {
        ObjectNode body = mapper.createObjectNode();
        body.put("statement", request.statement());
        body.put("warehouse_id", endpoint.warehouseId());
        body.put("wait_timeout", settings.waitTimeoutValue());
        body.put("on_wait_timeout", "CONTINUE");
        body.put("format", "JSON_ARRAY");
        body.put("disposition", disposition == ResultDisposition.EXTERNAL_LINKS
                ? "EXTERNAL_LINKS" : "INLINE");
        if (request.rowLimit() != null) {
            body.put("row_limit", request.rowLimit().longValue());
        }
        var catalog = request.catalog();
        if (catalog != null && !catalog.isBlank()) {
            body.put("catalog", catalog.strip());
        }
        var parameters = request.parameters();
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
