package com.bablsoft.accessflow.engine.elasticsearch;

import com.bablsoft.accessflow.core.api.ColumnMaskDirective;
import com.bablsoft.accessflow.core.api.DatasourceConnectionDescriptor;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.MaskingStrategy;
import com.bablsoft.accessflow.core.api.QueryEngineContext;
import com.bablsoft.accessflow.core.api.QueryEngineDryRunRequest;
import com.bablsoft.accessflow.core.api.QueryEngineExecutionRequest;
import com.bablsoft.accessflow.core.api.QueryExecutionRequest;
import com.bablsoft.accessflow.core.api.QueryExecutionResult;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.RowSecurityDirective;
import com.bablsoft.accessflow.core.api.RowSecurityOperator;
import com.bablsoft.accessflow.core.api.SelectExecutionResult;
import com.bablsoft.accessflow.core.api.SslMode;
import com.bablsoft.accessflow.core.api.UpdateExecutionResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the second {@link com.bablsoft.accessflow.core.api.QueryEngine} provider — the OpenSearch
 * flavour backed by the HttpClient 5 transport — drives the full SPI end to end against a real
 * single-node OpenSearch container (security plugin disabled, plain HTTP). The engine logic is
 * shared with Elasticsearch, so this is a focused smoke test of the {@link OpenSearchTransport};
 * Testcontainers has no OpenSearch module, hence the generic {@link GenericContainer}.
 */
class OpenSearchQueryEngineIntegrationTest {

    @SuppressWarnings("resource")
    private static final GenericContainer<?> CONTAINER = new GenericContainer<>(
            DockerImageName.parse("opensearchproject/opensearch:2.11.1"))
            .withExposedPorts(9200)
            .withEnv("discovery.type", "single-node")
            .withEnv("DISABLE_SECURITY_PLUGIN", "true")
            .withEnv("DISABLE_INSTALL_DEMO_CONFIG", "true")
            .withEnv("OPENSEARCH_JAVA_OPTS", "-Xms512m -Xmx512m")
            .waitingFor(Wait.forHttp("/").forStatusCode(200))
            .withStartupTimeout(Duration.ofMinutes(3));

    private static OpenSearchQueryEngine engine;
    private static DatasourceConnectionDescriptor descriptor;
    private static final UUID DS_ID = UUID.randomUUID();

    @BeforeAll
    static void startContainer() {
        CONTAINER.start();
        engine = new OpenSearchQueryEngine();
        engine.initialize(new QueryEngineContext(TestMessages.keyEcho(), ciphertext -> ciphertext,
                Map.of(), Clock.systemUTC()));
        descriptor = new DatasourceConnectionDescriptor(DS_ID, UUID.randomUUID(), DbType.OPENSEARCH,
                CONTAINER.getHost(), CONTAINER.getMappedPort(9200), null, "", "", SslMode.DISABLE,
                10, 10000, false, null, false, null, "opensearch", null, null, null, null, true,
                null, null);
    }

    @AfterAll
    static void stopContainer() {
        if (engine != null) {
            engine.shutdown();
        }
        CONTAINER.stop();
    }

    private static QueryExecutionResult exec(String sql, QueryType type,
                                             List<ColumnMaskDirective> masks,
                                             List<RowSecurityDirective> rls) {
        var request = new QueryExecutionRequest(DS_ID, sql, type, null, null, List.of(), masks, rls,
                false, null);
        return engine.execute(new QueryEngineExecutionRequest(request, descriptor, 10000,
                Duration.ofSeconds(30)));
    }

    @Test
    void drivesSearchRowSecurityMaskingAndIntrospectionEndToEnd() {
        exec("{\"bulk\":\"events\",\"operations\":["
                + "{\"document\":{\"tenant\":\"acme\",\"user\":{\"email\":\"a@x.io\"}}},"
                + "{\"document\":{\"tenant\":\"globex\",\"user\":{\"email\":\"c@x.io\"}}}]}",
                QueryType.INSERT, List.of(), List.of());

        var all = (SelectExecutionResult) exec("{\"search\":\"events\"}", QueryType.SELECT,
                List.of(), List.of());
        assertThat(all.rowCount()).isEqualTo(2);

        var policyId = UUID.randomUUID();
        var directive = new RowSecurityDirective(policyId, "events", "tenant",
                RowSecurityOperator.EQUALS, List.of("acme"));
        var mask = new ColumnMaskDirective("user.email", MaskingStrategy.EMAIL, Map.of(),
                UUID.randomUUID());
        var filtered = (SelectExecutionResult) exec("{\"search\":\"events\"}", QueryType.SELECT,
                List.of(mask), List.of(directive));
        assertThat(filtered.rowCount()).isEqualTo(1);
        assertThat(filtered.appliedRowSecurityPolicyIds()).containsExactly(policyId);
        int userCol = filtered.columns().stream().map(c -> c.name()).toList().indexOf("user");
        @SuppressWarnings("unchecked")
        var user = (Map<String, Object>) filtered.rows().get(0).get(userCol);
        assertThat((String) user.get("email")).contains("***@");

        // AF-624 preflight: the governed _count of the delete's pre-image, without mutating.
        var deleteSql = "{\"delete_by_query\":\"events\",\"query\":{\"term\":{\"tenant\":\"globex\"}}}";
        var preflight = engine.countAffectedRows(new QueryEngineDryRunRequest(
                new QueryExecutionRequest(DS_ID, deleteSql, QueryType.DELETE, null, null, List.of(),
                        List.of(), List.of(), false, null),
                descriptor, Duration.ofSeconds(30)));
        assertThat(preflight.supported()).isTrue();
        assertThat(preflight.engineId()).isEqualTo("opensearch");
        assertThat(preflight.affectedRows()).isEqualTo(1L);

        var delete = (UpdateExecutionResult) exec(deleteSql,
                QueryType.DELETE, List.of(), List.of());
        assertThat(delete.rowsAffected()).isEqualTo(1);

        var schema = engine.introspectSchema(descriptor);
        assertThat(schema.schemas().get(0).tables()).anyMatch(t -> t.name().equals("events"));
        assertThat(engine.testConnection(descriptor).ok()).isTrue();
    }
}
