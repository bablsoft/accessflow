package com.bablsoft.accessflow.engine.elasticsearch;

import com.bablsoft.accessflow.core.api.ColumnMaskDirective;
import com.bablsoft.accessflow.core.api.ConnectionTestResult;
import com.bablsoft.accessflow.core.api.DatasourceConnectionDescriptor;
import com.bablsoft.accessflow.core.api.DatasourceConnectionTestException;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.MaskingStrategy;
import com.bablsoft.accessflow.core.api.QueryAffectedRowsResult;
import com.bablsoft.accessflow.core.api.QueryDryRunResult;
import com.bablsoft.accessflow.core.api.QueryEngineContext;
import com.bablsoft.accessflow.core.api.QueryEngineDryRunRequest;
import com.bablsoft.accessflow.core.api.QueryEngineExecutionRequest;
import com.bablsoft.accessflow.core.api.QueryEngineSampleRequest;
import com.bablsoft.accessflow.core.api.QueryExecutionRequest;
import com.bablsoft.accessflow.core.api.QueryExecutionResult;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.RowSecurityDirective;
import com.bablsoft.accessflow.core.api.RowSecurityOperator;
import com.bablsoft.accessflow.core.api.SampleTableRequest;
import com.bablsoft.accessflow.core.api.SelectExecutionResult;
import com.bablsoft.accessflow.core.api.SslMode;
import com.bablsoft.accessflow.core.api.UpdateExecutionResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Drives the full {@link com.bablsoft.accessflow.core.api.QueryEngine} SPI against a real
 * single-node Elasticsearch container (security disabled, plain HTTP) — parse → execute → RLS /
 * masking → introspection → connection test, the acceptance pattern from the Mongo / Cassandra ITs.
 */
class ElasticsearchQueryEngineIntegrationTest {

    private static final DockerImageName IMAGE =
            DockerImageName.parse("docker.elastic.co/elasticsearch/elasticsearch:9.0.0");

    private static ElasticsearchContainer container;
    private static ElasticsearchQueryEngine engine;
    private static DatasourceConnectionDescriptor descriptor;
    private static final UUID DS_ID = UUID.randomUUID();

    @BeforeAll
    static void startContainer() {
        container = new ElasticsearchContainer(IMAGE)
                .withEnv("xpack.security.enabled", "false")
                .withEnv("discovery.type", "single-node");
        container.start();
        engine = new ElasticsearchQueryEngine();
        engine.initialize(new QueryEngineContext(TestMessages.keyEcho(), ciphertext -> ciphertext,
                Map.of(), Clock.systemUTC()));
        var address = container.getHttpHostAddress(); // host:port
        var host = address.substring(0, address.lastIndexOf(':'));
        var port = Integer.parseInt(address.substring(address.lastIndexOf(':') + 1));
        descriptor = new DatasourceConnectionDescriptor(DS_ID, UUID.randomUUID(),
                DbType.ELASTICSEARCH, host, port, null, "", "", SslMode.DISABLE, 10, 10000,
                false, null, false, null, "elasticsearch", null, null, null, null, true, null, null);
    }

    @AfterAll
    static void stopContainer() {
        if (engine != null) {
            engine.shutdown();
        }
        if (container != null) {
            container.stop();
        }
    }

    private static QueryExecutionResult exec(String sql, QueryType type) {
        return exec(sql, type, List.of(), List.of());
    }

    private static QueryExecutionResult exec(String sql, QueryType type,
                                             List<ColumnMaskDirective> masks,
                                             List<RowSecurityDirective> rls) {
        var request = new QueryExecutionRequest(DS_ID, sql, type, null, null, List.of(), masks, rls,
                false, null);
        return engine.execute(new QueryEngineExecutionRequest(request, descriptor, 10000,
                Duration.ofSeconds(30)));
    }

    private static QueryDryRunResult dryRun(String sql, List<RowSecurityDirective> rls) {
        var type = engine.parse(sql).type();
        var request = new QueryExecutionRequest(DS_ID, sql, type, null, null, List.of(), List.of(),
                rls, false, null);
        return engine.dryRun(new QueryEngineDryRunRequest(request, descriptor,
                Duration.ofSeconds(30)));
    }

    @Test
    void dryRunSearchValidatesQueryWithoutExecuting() {
        seed("logs-dryrun");
        var result = dryRun("{\"search\":\"logs-dryrun\",\"query\":{\"term\":{\"tenant\":\"acme\"}}}",
                List.of());
        assertThat(result.supported()).isTrue();
        assertThat(result.queryType()).isEqualTo(QueryType.SELECT);
        assertThat(result.plan()).isNotNull();
        assertThat(result.plan().operation()).isEqualTo("valid_query");
    }

    @Test
    void dryRunIndexOperationIsUnsupported() {
        var result = dryRun("{\"index\":\"logs-dryrun\",\"id\":\"9\",\"document\":{\"tenant\":\"x\"}}",
                List.of());
        assertThat(result.supported()).isFalse();
    }

    private static QueryAffectedRowsResult countAffected(String sql,
                                                         List<RowSecurityDirective> rls) {
        var type = engine.parse(sql).type();
        var request = new QueryExecutionRequest(DS_ID, sql, type, null, null, List.of(), List.of(),
                rls, false, null);
        return engine.countAffectedRows(new QueryEngineDryRunRequest(request, descriptor,
                Duration.ofSeconds(30)));
    }

    @Test
    void countAffectedRowsCountsDeleteByQueryWithoutMutating() {
        seed("logs-preflight");
        var result = countAffected(
                "{\"delete_by_query\":\"logs-preflight\",\"query\":{\"term\":{\"tenant\":\"acme\"}}}",
                List.of());
        assertThat(result.supported()).isTrue();
        assertThat(result.engineId()).isEqualTo("elasticsearch");
        assertThat(result.affectedRows()).isEqualTo(2L);
        // Non-mutating preflight: all three seeded documents survive.
        var count = (SelectExecutionResult) exec("{\"count\":\"logs-preflight\"}", QueryType.SELECT);
        assertThat(count.rows().get(0).get(0)).isEqualTo(3L);
    }

    @Test
    void countAffectedRowsAppliesRowSecurityToTheGovernedCount() {
        seed("logs-preflight");
        var directive = new RowSecurityDirective(UUID.randomUUID(), "logs-preflight", "tenant",
                RowSecurityOperator.EQUALS, List.of("acme"));
        var result = countAffected("{\"update_by_query\":\"logs-preflight\"}", List.of(directive));
        assertThat(result.supported()).isTrue();
        assertThat(result.affectedRows()).isEqualTo(2L);
    }

    @Test
    void countAffectedRowsIsUnsupportedForReads() {
        seed("logs-preflight");
        var result = countAffected("{\"search\":\"logs-preflight\"}", List.of());
        assertThat(result.supported()).isFalse();
        assertThat(result.engineId()).isEqualTo("elasticsearch");
    }

    @Test
    void dryRunAppliesRowSecurity() {
        seed("logs-dryrun");
        var directive = new RowSecurityDirective(UUID.randomUUID(), "logs-dryrun", "tenant",
                RowSecurityOperator.EQUALS, List.of("acme"));
        var result = dryRun("{\"search\":\"logs-dryrun\"}", List.of(directive));
        assertThat(result.supported()).isTrue();
        assertThat(result.appliedRowSecurityPolicyIds()).containsExactly(directive.policyId());
    }

    private static SelectExecutionResult sample(String index, int maxRows,
                                                List<RowSecurityDirective> rls,
                                                List<ColumnMaskDirective> masks) {
        var request = new SampleTableRequest(DS_ID, descriptor.databaseName(), index,
                List.of(), masks, rls, null, null);
        return (SelectExecutionResult) engine.sampleTable(new QueryEngineSampleRequest(request,
                descriptor, maxRows, Duration.ofSeconds(30)));
    }

    private static void seed(String index) {
        exec("{\"bulk\":\"" + index + "\",\"operations\":["
                + "{\"id\":\"1\",\"document\":{\"tenant\":\"acme\",\"user\":{\"email\":\"a@x.io\"}}},"
                + "{\"id\":\"2\",\"document\":{\"tenant\":\"acme\",\"user\":{\"email\":\"b@x.io\"}}},"
                + "{\"id\":\"3\",\"document\":{\"tenant\":\"globex\",\"user\":{\"email\":\"c@x.io\"}}}"
                + "]}", QueryType.INSERT);
    }

    @Test
    void searchAndCountReturnSeededDocuments() {
        seed("logs-read");
        var search = (SelectExecutionResult) exec("{\"search\":\"logs-read\"}", QueryType.SELECT);
        assertThat(search.rowCount()).isEqualTo(3);
        assertThat(search.columns()).extracting(c -> c.name()).contains("_id", "_index", "tenant");

        var count = (SelectExecutionResult) exec("{\"count\":\"logs-read\"}", QueryType.SELECT);
        assertThat(count.rows().get(0).get(0)).isEqualTo(3L);
    }

    @Test
    void rowSecurityFiltersHitsAndRecordsPolicyId() {
        seed("logs-rls");
        var policyId = UUID.randomUUID();
        var directive = new RowSecurityDirective(policyId, "logs-rls", "tenant",
                RowSecurityOperator.EQUALS, List.of("acme"));
        var result = (SelectExecutionResult) exec(
                "{\"search\":\"logs-rls\"}", QueryType.SELECT, List.of(), List.of(directive));
        assertThat(result.rowCount()).isEqualTo(2);
        assertThat(result.appliedRowSecurityPolicyIds()).containsExactly(policyId);

        var count = (SelectExecutionResult) exec(
                "{\"count\":\"logs-rls\"}", QueryType.SELECT, List.of(), List.of(directive));
        assertThat(count.rows().get(0).get(0)).isEqualTo(2L);
    }

    @Test
    void maskingRedactsNestedSourceField() {
        seed("logs-mask");
        var mask = new ColumnMaskDirective("user.email", MaskingStrategy.EMAIL, Map.of(),
                UUID.randomUUID());
        var result = (SelectExecutionResult) exec("{\"search\":\"logs-mask\"}", QueryType.SELECT,
                List.of(mask), List.of());
        int userCol = result.columns().stream().map(c -> c.name()).toList().indexOf("user");
        for (var row : result.rows()) {
            @SuppressWarnings("unchecked")
            var user = (Map<String, Object>) row.get(userCol);
            assertThat((String) user.get("email")).contains("***@");
        }
    }

    // All four sample tests reuse a single index. seed() re-indexes the same three fixed-id docs
    // (idempotent), so the index is auto-created exactly once — avoiding the rapid successive
    // index-creation that a single-node Testcontainers ES can reject mid-burst.
    private static final String SAMPLE_INDEX = "logs-sample";

    @Test
    void sampleTableReturnsIndexDocuments() {
        seed(SAMPLE_INDEX);
        var result = sample(SAMPLE_INDEX, 100, List.of(), List.of());
        assertThat(result.rowCount()).isEqualTo(3);
        assertThat(result.columns()).extracting(c -> c.name()).contains("_id", "tenant");
    }

    @Test
    void sampleTableHonoursRowCap() {
        seed(SAMPLE_INDEX);
        var result = sample(SAMPLE_INDEX, 2, List.of(), List.of());
        assertThat(result.rowCount()).isEqualTo(2);
        assertThat(result.truncated()).isTrue();
    }

    @Test
    void sampleTableAppliesRowSecurity() {
        seed(SAMPLE_INDEX);
        var policyId = UUID.randomUUID();
        var directive = new RowSecurityDirective(policyId, SAMPLE_INDEX, "tenant",
                RowSecurityOperator.EQUALS, List.of("acme"));
        var result = sample(SAMPLE_INDEX, 100, List.of(directive), List.of());
        assertThat(result.rowCount()).isEqualTo(2);
        assertThat(result.appliedRowSecurityPolicyIds()).containsExactly(policyId);
    }

    @Test
    void sampleTableAppliesNestedColumnMask() {
        seed(SAMPLE_INDEX);
        var mask = new ColumnMaskDirective("user.email", MaskingStrategy.EMAIL, Map.of(),
                UUID.randomUUID());
        var result = sample(SAMPLE_INDEX, 100, List.of(), List.of(mask));
        int userCol = result.columns().stream().map(c -> c.name()).toList().indexOf("user");
        for (var row : result.rows()) {
            @SuppressWarnings("unchecked")
            var user = (Map<String, Object>) row.get(userCol);
            assertThat((String) user.get("email")).contains("***@");
        }
    }

    @Test
    void updateByQueryAndDeleteByQueryReportAffectedCounts() {
        seed("logs-mutate");
        var update = (UpdateExecutionResult) exec(
                "{\"update_by_query\":\"logs-mutate\",\"query\":{\"term\":{\"tenant\":\"acme\"}}}",
                QueryType.UPDATE);
        assertThat(update.rowsAffected()).isEqualTo(2);

        var delete = (UpdateExecutionResult) exec(
                "{\"delete_by_query\":\"logs-mutate\",\"query\":{\"term\":{\"tenant\":\"globex\"}}}",
                QueryType.DELETE);
        assertThat(delete.rowsAffected()).isEqualTo(1);

        var count = (SelectExecutionResult) exec("{\"count\":\"logs-mutate\"}", QueryType.SELECT);
        assertThat(count.rows().get(0).get(0)).isEqualTo(2L);
    }

    @Test
    void indexManagementDdlSucceeds() {
        var create = (UpdateExecutionResult) exec(
                "{\"create_index\":\"ddl-index\",\"mappings\":{\"properties\":{\"a\":{\"type\":\"keyword\"}}}}",
                QueryType.DDL);
        assertThat(create.rowsAffected()).isZero();
        var putMapping = (UpdateExecutionResult) exec(
                "{\"put_mapping\":\"ddl-index\",\"properties\":{\"b\":{\"type\":\"integer\"}}}",
                QueryType.DDL);
        assertThat(putMapping.rowsAffected()).isZero();
        var delete = (UpdateExecutionResult) exec("{\"delete_index\":\"ddl-index\"}", QueryType.DDL);
        assertThat(delete.rowsAffected()).isZero();
    }

    @Test
    void introspectionListsIndicesWithFieldsAndSyntheticIdPrimaryKey() {
        seed("logs-introspect");
        var schema = engine.introspectSchema(descriptor);
        var table = schema.schemas().get(0).tables().stream()
                .filter(t -> t.name().equals("logs-introspect"))
                .findFirst()
                .orElseThrow();
        assertThat(table.columns()).anyMatch(c -> c.name().equals("_id") && c.primaryKey());
        assertThat(table.columns()).anyMatch(c -> c.name().equals("tenant"));
        assertThat(table.columns()).anyMatch(c -> c.name().equals("user.email"));
    }

    @Test
    void connectionTestSucceedsAndFailsForUnreachableHost() {
        ConnectionTestResult ok = engine.testConnection(descriptor);
        assertThat(ok.ok()).isTrue();

        var unreachable = new DatasourceConnectionDescriptor(UUID.randomUUID(), UUID.randomUUID(),
                DbType.ELASTICSEARCH, "127.0.0.1", 1, null, "", "", SslMode.DISABLE, 10, 10000,
                false, null, false, null, "elasticsearch", null, null, null, null, true, null, null);
        assertThatThrownBy(() -> engine.testConnection(unreachable))
                .isInstanceOf(DatasourceConnectionTestException.class);
    }

    @Test
    void searchTruncatesAtTheEffectiveRowCap() {
        exec("{\"bulk\":\"logs-trunc\",\"operations\":["
                + "{\"document\":{\"n\":1}},{\"document\":{\"n\":2}},{\"document\":{\"n\":3}}]}",
                QueryType.INSERT);
        var request = new QueryExecutionRequest(DS_ID, "{\"search\":\"logs-trunc\"}",
                QueryType.SELECT, null, null, List.of(), List.of(), List.of(), false, null);
        var result = (SelectExecutionResult) engine.execute(
                new QueryEngineExecutionRequest(request, descriptor, 2, Duration.ofSeconds(30)));
        assertThat(result.truncated()).isTrue();
        assertThat(result.rowCount()).isEqualTo(2);
    }
}
