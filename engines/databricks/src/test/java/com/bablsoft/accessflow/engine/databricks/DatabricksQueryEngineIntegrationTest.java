package com.bablsoft.accessflow.engine.databricks;

import com.bablsoft.accessflow.core.api.ColumnMaskDirective;
import com.bablsoft.accessflow.core.api.DatasourceConnectionDescriptor;
import com.bablsoft.accessflow.core.api.DatasourceConnectionTestException;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.InvalidSqlException;
import com.bablsoft.accessflow.core.api.MaskingStrategy;
import com.bablsoft.accessflow.core.api.QueryEngineContext;
import com.bablsoft.accessflow.core.api.QueryEngineExecutionRequest;
import com.bablsoft.accessflow.core.api.QueryEngineSampleRequest;
import com.bablsoft.accessflow.core.api.QueryExecutionFailedException;
import com.bablsoft.accessflow.core.api.QueryExecutionRequest;
import com.bablsoft.accessflow.core.api.QueryExecutionTimeoutException;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.RowSecurityDirective;
import com.bablsoft.accessflow.core.api.RowSecurityOperator;
import com.bablsoft.accessflow.core.api.SampleTableRequest;
import com.bablsoft.accessflow.core.api.SelectExecutionResult;
import com.bablsoft.accessflow.core.api.SslMode;
import com.bablsoft.accessflow.core.api.UnrewritableRowSecurityException;
import com.bablsoft.accessflow.core.api.UpdateExecutionResult;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Drives the full engine through the {@link DatabricksQueryEngine} SPI facade — the exact surface
 * the host calls after ServiceLoader discovery — against an in-process
 * {@link com.sun.net.httpserver.HttpServer} stub of the Statement Execution API (no Databricks
 * account, no containers), proving the acceptance behaviour: submit/poll/cancel lifecycle
 * (immediate SUCCEEDED, PENDING→poll→SUCCEEDED, FAILED with the verbatim message, deadline expiry
 * cancelling the statement), auth failure surfacing, chunked inline results, truncation via
 * {@code row_limit}, WHERE-spliced row security with typed named parameters (incl. deny-all making
 * ZERO HTTP calls and INSERT-into-policied rejection), column masking, DML affected-rows parsing,
 * DDL, information_schema introspection, connection probing, and parse rejections.
 */
class DatabricksQueryEngineIntegrationTest {

    private static final String WAREHOUSE = "whstub";
    private static final String PAT = "pat-token-123";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private static StubApi stub;
    private static DatabricksQueryEngine engine;
    private static DatasourceConnectionDescriptor descriptor;

    @BeforeAll
    static void startStubAndEngine() throws IOException {
        stub = new StubApi();
        engine = new DatabricksQueryEngine();
        engine.initialize(new QueryEngineContext(TestMessages.keyEcho(), ciphertext -> ciphertext,
                Map.of("poll-interval", "PT0.05S", "wait-timeout", "PT5S"), Clock.systemUTC()));
        descriptor = descriptor(stub.url("/sql/1.0/warehouses/" + WAREHOUSE));
    }

    @AfterAll
    static void stopEverything() {
        engine.shutdown();
        stub.close();
    }

    @BeforeEach
    void resetStub() {
        stub.reset();
    }

    @Test
    void engineIdMatchesConnectorId() {
        assertThat(engine.engineId()).isEqualTo("databricks");
    }

    @Test
    void parseClassifiesAndRejects() {
        assertThat(engine.parse("SELECT * FROM main.sales.orders").type())
                .isEqualTo(QueryType.SELECT);
        assertThatThrownBy(() -> engine.parse("SHOW TABLES"))
                .isInstanceOf(InvalidSqlException.class)
                .hasMessageContaining("error.databricks.unsupported_statement");
        assertThatThrownBy(() -> engine.parse("SELECT 1; SELECT 2"))
                .isInstanceOf(InvalidSqlException.class)
                .hasMessageContaining("error.databricks.multiple_statements");
        assertThatThrownBy(() -> engine.parse("SELECT * FROM t WHERE id = :id"))
                .isInstanceOf(InvalidSqlException.class)
                .hasMessageContaining("error.databricks.parameter_marker_forbidden");
        assertThat(stub.requests).isEmpty();
    }

    @Test
    void immediateSucceededSelectReturnsTypedRowsAndSendsTheFullEnvelope() {
        stub.submitResponses.add(succeeded("st1",
                columns(col("id", "BIGINT"), col("name", "STRING")),
                "[[\"1\",\"Ada\"],[\"2\",\"Bo\"]]", false));
        var result = (SelectExecutionResult) execute("SELECT * FROM orders", QueryType.SELECT,
                100, List.of(), List.of(), TIMEOUT);
        assertThat(result.rowCount()).isEqualTo(2);
        assertThat(result.rows().get(0)).containsExactly(1L, "Ada");
        assertThat(result.columns()).extracting("name").containsExactly("id", "name");
        assertThat(result.truncated()).isFalse();

        var submit = stub.requests.get(0);
        assertThat(submit.method()).isEqualTo("POST");
        assertThat(submit.path()).isEqualTo("/api/2.0/sql/statements");
        assertThat(submit.authorization()).isEqualTo("Bearer " + PAT);
        assertThat(submit.body())
                .contains("\"statement\":\"SELECT * FROM orders\"")
                .contains("\"warehouse_id\":\"" + WAREHOUSE + "\"")
                .contains("\"wait_timeout\":\"5s\"")
                .contains("\"on_wait_timeout\":\"CONTINUE\"")
                .contains("\"format\":\"JSON_ARRAY\"")
                .contains("\"disposition\":\"INLINE\"")
                .contains("\"row_limit\":101")
                .contains("\"catalog\":\"main\"");
        assertThat(submit.body()).doesNotContain("parameters");
    }

    @Test
    void pendingStatementIsPolledUntilSucceeded() {
        stub.submitResponses.add(state("st2", "PENDING"));
        stub.pollResponses.add(state("st2", "RUNNING"));
        stub.pollResponses.add(succeeded("st2", columns(col("n", "INT")), "[[\"7\"]]", false));
        var result = (SelectExecutionResult) execute("SELECT n FROM t", QueryType.SELECT, 10,
                List.of(), List.of(), TIMEOUT);
        assertThat(result.rows()).containsExactly(List.of(7L));
        assertThat(stub.requests).extracting(StubApi.Recorded::method)
                .containsExactly("POST", "GET", "GET");
        assertThat(stub.requests.get(1).path()).isEqualTo("/api/2.0/sql/statements/st2");
    }

    @Test
    void failedStateSurfacesTheVerbatimApiMessage() {
        stub.submitResponses.add("""
                {"statement_id":"st3","status":{"state":"FAILED","error":{
                 "error_code":"BAD_REQUEST","message":"[TABLE_OR_VIEW_NOT_FOUND] Table nope"}}}""");
        assertThatThrownBy(() -> execute("SELECT * FROM nope", QueryType.SELECT, 10, List.of(),
                List.of(), TIMEOUT))
                .isInstanceOf(QueryExecutionFailedException.class)
                .satisfies(e -> assertThat(((QueryExecutionFailedException) e).detail())
                        .isEqualTo("[TABLE_OR_VIEW_NOT_FOUND] Table nope"));
    }

    @Test
    void slowStatementIsCancelledOnTheDeadline() {
        stub.submitResponses.add(state("st4", "PENDING"));
        stub.defaultPollResponse = state("st4", "RUNNING");
        assertThatThrownBy(() -> execute("SELECT * FROM slow_t", QueryType.SELECT, 10, List.of(),
                List.of(), Duration.ofMillis(300)))
                .isInstanceOf(QueryExecutionTimeoutException.class);
        assertThat(stub.cancels.get()).isEqualTo(1);
        assertThat(stub.requests)
                .anyMatch(r -> r.path().equals("/api/2.0/sql/statements/st4/cancel"));
    }

    @Test
    void unauthorizedResponseSurfacesAsExecutionFailure() {
        stub.submitStatus = 401;
        stub.submitResponses.add(
                "{\"error_code\":\"UNAUTHENTICATED\",\"message\":\"Invalid access token\"}");
        assertThatThrownBy(() -> execute("SELECT 1", QueryType.SELECT, 10, List.of(), List.of(),
                TIMEOUT))
                .isInstanceOf(QueryExecutionFailedException.class)
                .satisfies(e -> {
                    assertThat(((QueryExecutionFailedException) e).detail())
                            .isEqualTo("Invalid access token");
                    assertThat(((QueryExecutionFailedException) e).vendorCode()).isEqualTo(401);
                });
    }

    @Test
    void chunkedInlineResultFollowsNextChunkIndex() {
        stub.submitResponses.add("""
                {"statement_id":"st5","status":{"state":"SUCCEEDED"},
                 "manifest":{"schema":{"columns":[{"name":"id","type_name":"INT","position":0}]},
                             "total_row_count":4,"truncated":false},
                 "result":{"data_array":[["1"],["2"]],"next_chunk_index":1}}""");
        stub.chunks.put(1, "{\"data_array\":[[\"3\"],[\"4\"]]}");
        var result = (SelectExecutionResult) execute("SELECT id FROM t", QueryType.SELECT, 100,
                List.of(), List.of(), TIMEOUT);
        assertThat(result.rows()).containsExactly(List.of(1L), List.of(2L), List.of(3L),
                List.of(4L));
        assertThat(stub.requests)
                .anyMatch(r -> r.path().equals("/api/2.0/sql/statements/st5/result/chunks/1"));
    }

    @Test
    void rowLimitSentinelDrivesTruncation() {
        stub.submitResponses.add(succeeded("st6", columns(col("id", "INT")),
                "[[\"1\"],[\"2\"],[\"3\"]]", false));
        var result = (SelectExecutionResult) execute("SELECT id FROM t", QueryType.SELECT, 2,
                List.of(), List.of(), TIMEOUT);
        assertThat(result.rows()).hasSize(2);
        assertThat(result.truncated()).isTrue();
        assertThat(stub.requests.get(0).body()).contains("\"row_limit\":3");
    }

    @Test
    void rowSecuritySplicesTheStatementAndBindsTypedNamedParameters() {
        stub.submitResponses.add(succeeded("st7", columns(col("id", "INT")), "[[\"1\"]]", false));
        var policy = UUID.randomUUID();
        var result = (SelectExecutionResult) execute("SELECT id FROM orders", QueryType.SELECT,
                10,
                List.of(new RowSecurityDirective(policy, "orders", "tenant",
                                RowSecurityOperator.EQUALS, List.of("acme")),
                        new RowSecurityDirective(policy, "orders", "region",
                                RowSecurityOperator.IN, List.of(1L, true))),
                List.of(), TIMEOUT);
        assertThat(result.appliedRowSecurityPolicyIds()).containsExactly(policy);
        var body = stub.requests.get(0).body();
        assertThat(body).contains("SELECT id FROM orders WHERE (`tenant` = :afp_1"
                + " AND `region` IN (:afp_2, :afp_3))");
        assertThat(body).contains(
                "{\"name\":\"afp_1\",\"type\":\"STRING\",\"value\":\"acme\"}");
        assertThat(body).contains("{\"name\":\"afp_2\",\"type\":\"BIGINT\",\"value\":\"1\"}");
        assertThat(body).contains("{\"name\":\"afp_3\",\"type\":\"BOOLEAN\",\"value\":\"true\"}");
    }

    @Test
    void rowSecurityDenyAllShortCircuitsWithZeroHttpCalls() {
        var policy = UUID.randomUUID();
        var result = (SelectExecutionResult) execute("SELECT * FROM orders", QueryType.SELECT, 10,
                List.of(new RowSecurityDirective(policy, "orders", "tenant",
                        RowSecurityOperator.IN, List.of())),
                List.of(), TIMEOUT);
        assertThat(result.rowCount()).isZero();
        assertThat(result.appliedRowSecurityPolicyIds()).containsExactly(policy);
        assertThat(stub.requests).isEmpty();

        var dml = (UpdateExecutionResult) execute("DELETE FROM orders", QueryType.DELETE, 10,
                List.of(new RowSecurityDirective(policy, "orders", "tenant",
                        RowSecurityOperator.IN, List.of())),
                List.of(), TIMEOUT);
        assertThat(dml.rowsAffected()).isZero();
        assertThat(stub.requests).isEmpty();
    }

    @Test
    void insertIntoPoliciedTableIsRejectedWithoutHttp() {
        assertThatThrownBy(() -> execute("INSERT INTO orders VALUES (1)", QueryType.INSERT, 10,
                List.of(new RowSecurityDirective(UUID.randomUUID(), "orders", "tenant",
                        RowSecurityOperator.EQUALS, List.of("acme"))),
                List.of(), TIMEOUT))
                .isInstanceOf(UnrewritableRowSecurityException.class);
        assertThat(stub.requests).isEmpty();
    }

    @Test
    void columnMasksApplyToTheFetchedResult() {
        stub.submitResponses.add(succeeded("st8",
                columns(col("email", "STRING"), col("salary", "DECIMAL(10,2)")),
                "[[\"ada@x.io\",\"1234.56\"]]", false));
        var mask = new ColumnMaskDirective("email", MaskingStrategy.EMAIL, Map.of(),
                UUID.randomUUID());
        var result = (SelectExecutionResult) execute("SELECT * FROM people", QueryType.SELECT, 10,
                List.of(), List.of(mask), TIMEOUT);
        assertThat(result.rows().get(0)).containsExactly("a***@x.io", new BigDecimal("1234.56"));
        assertThat(result.columns().get(0).restricted()).isTrue();
        assertThat(result.appliedMaskingPolicyIds()).containsExactly(mask.policyId());
    }

    @Test
    void dmlParsesNumAffectedRowsAndOmitsRowLimit() {
        stub.submitResponses.add(succeeded("st9", columns(col("num_affected_rows", "BIGINT")),
                "[[\"5\"]]", false));
        var result = (UpdateExecutionResult) execute("UPDATE orders SET total = 0",
                QueryType.UPDATE, 10, List.of(), List.of(), TIMEOUT);
        assertThat(result.rowsAffected()).isEqualTo(5);
        assertThat(stub.requests.get(0).body()).doesNotContain("row_limit");
    }

    @Test
    void ddlReturnsZeroAffectedRows() {
        stub.submitResponses.add(succeeded("st10", "[]", "[]", false));
        var result = (UpdateExecutionResult) execute("CREATE TABLE t (id INT)", QueryType.DDL, 10,
                List.of(), List.of(), TIMEOUT);
        assertThat(result.rowsAffected()).isZero();
    }

    @Test
    void sampleTableRunsTheGovernedSelect() {
        stub.statementRules.add(Map.entry(
                s -> s.equals("SELECT * FROM `sales`.`orders`"),
                succeeded("st11", columns(col("id", "INT")), "[[\"1\"],[\"2\"]]", false)));
        var request = new SampleTableRequest(descriptor.id(), "sales", "orders", List.of(),
                List.of(), List.of(), null, null);
        var result = (SelectExecutionResult) engine.sampleTable(new QueryEngineSampleRequest(
                request, descriptor, 10, TIMEOUT));
        assertThat(result.rowCount()).isEqualTo(2);
    }

    @Test
    void testConnectionProbesWithSelectOne() {
        stub.statementRules.add(Map.entry(s -> s.equals("SELECT 1"),
                succeeded("st12", columns(col("1", "INT")), "[[\"1\"]]", false)));
        var result = engine.testConnection(descriptor);
        assertThat(result.ok()).isTrue();
        assertThat(result.message()).isEqualTo("ok");
    }

    @Test
    void testConnectionFailureSurfacesTheApiMessage() {
        stub.submitStatus = 403;
        stub.submitResponses.add(
                "{\"error_code\":\"PERMISSION_DENIED\",\"message\":\"no can do\"}");
        assertThatThrownBy(() -> engine.testConnection(descriptor))
                .isInstanceOf(DatasourceConnectionTestException.class)
                .hasMessage("no can do");
    }

    @Test
    void introspectionGroupsInformationSchemaRowsIntoTheSchemaView() {
        stub.statementRules.add(Map.entry(s -> s.contains("information_schema.tables"),
                succeeded("stT", columns(col("table_schema", "STRING"),
                                col("table_name", "STRING")),
                        "[[\"hr\",\"people\"],[\"sales\",\"orders\"]]", false)));
        stub.statementRules.add(Map.entry(s -> s.contains("information_schema.columns"),
                succeeded("stC", columns(col("table_schema", "STRING"),
                                col("table_name", "STRING"), col("column_name", "STRING"),
                                col("data_type", "STRING"), col("is_nullable", "STRING")),
                        "[[\"hr\",\"people\",\"id\",\"BIGINT\",\"NO\"],"
                                + "[\"hr\",\"people\",\"name\",\"STRING\",\"YES\"],"
                                + "[\"sales\",\"orders\",\"total\",\"DECIMAL(10,2)\",\"YES\"]]",
                        false)));
        var view = engine.introspectSchema(descriptor);
        assertThat(view.schemas()).extracting("name").containsExactly("hr", "sales");
        var people = view.schemas().get(0).tables().get(0);
        assertThat(people.name()).isEqualTo("people");
        assertThat(people.columns()).extracting("name").containsExactly("id", "name");
        assertThat(people.columns().get(0).nullable()).isFalse();
        assertThat(people.columns().get(1).nullable()).isTrue();
        // Catalog-qualified because the datasource pins database_name = "main".
        assertThat(stub.requests.get(0).body()).contains("`main`.information_schema.tables");
    }

    @Test
    void endpointMisconfigurationFailsBothPaths() {
        var broken = descriptor("/not/a/warehouse/path");
        assertThatThrownBy(() -> execute(broken, "SELECT 1", QueryType.SELECT, 10, List.of(),
                List.of(), TIMEOUT))
                .isInstanceOf(QueryExecutionFailedException.class)
                .satisfies(e -> assertThat(((QueryExecutionFailedException) e).detail())
                        .isEqualTo("error.databricks.warehouse_path_invalid"));
        assertThatThrownBy(() -> engine.testConnection(broken))
                .isInstanceOf(DatasourceConnectionTestException.class)
                .hasMessage("error.databricks.warehouse_path_invalid");
        assertThat(stub.requests).isEmpty();
    }

    // ---- helpers --------------------------------------------------------------------------------

    private static Object execute(String sql, QueryType type, int maxRows,
                                  List<RowSecurityDirective> rls,
                                  List<ColumnMaskDirective> masks, Duration timeout) {
        return execute(descriptor, sql, type, maxRows, rls, masks, timeout);
    }

    private static Object execute(DatasourceConnectionDescriptor target, String sql,
                                  QueryType type, int maxRows, List<RowSecurityDirective> rls,
                                  List<ColumnMaskDirective> masks, Duration timeout) {
        var request = new QueryExecutionRequest(target.id(), sql, type, null, null,
                List.of(), masks, rls, false, List.of(sql));
        return engine.execute(new QueryEngineExecutionRequest(request, target, maxRows, timeout));
    }

    /**
     * The installed backend snapshot predates the DATABRICKS DbType constant; the engine never
     * reads {@code dbType}, so CUSTOM keeps the test decoupled from the host enum.
     */
    private static DatasourceConnectionDescriptor descriptor(String override) {
        return new DatasourceConnectionDescriptor(UUID.randomUUID(), UUID.randomUUID(),
                DbType.DATABRICKS, "workspace.example.com", null, "main", "token", PAT,
                SslMode.REQUIRE, 10, 1000, true, null, false, null, "databricks", override, null,
                null, null, true, null);
    }

    private static String col(String name, String typeName) {
        return "{\"name\":\"" + name + "\",\"type_name\":\"" + typeName + "\"}";
    }

    private static String columns(String... cols) {
        return "[" + String.join(",", cols) + "]";
    }

    private static String state(String id, String state) {
        return "{\"statement_id\":\"" + id + "\",\"status\":{\"state\":\"" + state + "\"}}";
    }

    private static String succeeded(String id, String columnsJson, String dataArray,
                                    boolean truncated) {
        return "{\"statement_id\":\"" + id + "\",\"status\":{\"state\":\"SUCCEEDED\"},"
                + "\"manifest\":{\"schema\":{\"columns\":" + columnsJson + "},"
                + "\"truncated\":" + truncated + "},"
                + "\"result\":{\"data_array\":" + dataArray + "}}";
    }

    /** Programmable in-process stub of the Statement Execution API. */
    private static final class StubApi {

        record Recorded(String method, String path, String body, String authorization) {
        }

        final List<Recorded> requests = Collections.synchronizedList(new ArrayList<>());
        final ConcurrentLinkedDeque<String> submitResponses = new ConcurrentLinkedDeque<>();
        final ConcurrentLinkedDeque<String> pollResponses = new ConcurrentLinkedDeque<>();
        final Map<Integer, String> chunks = new java.util.concurrent.ConcurrentHashMap<>();
        final List<Map.Entry<Predicate<String>, String>> statementRules =
                new CopyOnWriteArrayList<>();
        final AtomicInteger cancels = new AtomicInteger();
        volatile String defaultPollResponse;
        volatile int submitStatus = 200;

        private final HttpServer server;

        StubApi() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", this::handle);
            server.start();
        }

        String url(String path) {
            return "http://127.0.0.1:" + server.getAddress().getPort() + path;
        }

        void reset() {
            requests.clear();
            submitResponses.clear();
            pollResponses.clear();
            chunks.clear();
            statementRules.clear();
            cancels.set(0);
            defaultPollResponse = null;
            submitStatus = 200;
        }

        void close() {
            server.stop(0);
        }

        private void handle(HttpExchange exchange) throws IOException {
            var body = new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8);
            var path = exchange.getRequestURI().getPath();
            var method = exchange.getRequestMethod();
            requests.add(new Recorded(method, path, body,
                    exchange.getRequestHeaders().getFirst("Authorization")));
            if ("POST".equals(method) && path.equals("/api/2.0/sql/statements")) {
                respond(exchange, submitStatus, submitResponse(body));
                return;
            }
            if ("POST".equals(method) && path.endsWith("/cancel")) {
                cancels.incrementAndGet();
                respond(exchange, 200, "{}");
                return;
            }
            if ("GET".equals(method) && path.contains("/result/chunks/")) {
                var index = Integer.parseInt(path.substring(path.lastIndexOf('/') + 1));
                respond(exchange, 200, chunks.getOrDefault(index, "{}"));
                return;
            }
            if ("GET".equals(method) && path.startsWith("/api/2.0/sql/statements/")) {
                var next = pollResponses.pollFirst();
                respond(exchange, 200, next != null ? next
                        : defaultPollResponse != null ? defaultPollResponse : "{}");
                return;
            }
            respond(exchange, 404, "{\"message\":\"unexpected " + method + " " + path + "\"}");
        }

        private String submitResponse(String body) {
            for (var rule : statementRules) {
                var statement = statementOf(body);
                if (statement != null && rule.getKey().test(statement)) {
                    return rule.getValue();
                }
            }
            var queued = submitResponses.pollFirst();
            return queued != null ? queued
                    : "{\"message\":\"no stubbed submit response\",\"error_code\":\"STUB\"}";
        }

        /** Crude but sufficient: extracts the submitted "statement" JSON string field. */
        private static String statementOf(String body) {
            var marker = "\"statement\":\"";
            int start = body.indexOf(marker);
            if (start < 0) {
                return null;
            }
            start += marker.length();
            var sb = new StringBuilder();
            for (int i = start; i < body.length(); i++) {
                char c = body.charAt(i);
                if (c == '\\' && i + 1 < body.length()) {
                    sb.append(body.charAt(++i));
                    continue;
                }
                if (c == '"') {
                    break;
                }
                sb.append(c);
            }
            return sb.toString();
        }

        private static void respond(HttpExchange exchange, int status, String body)
                throws IOException {
            var bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (var out = exchange.getResponseBody()) {
                out.write(bytes);
            }
            exchange.close();
        }
    }
}
