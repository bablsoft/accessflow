package com.bablsoft.accessflow.engine.neo4j;

import com.bablsoft.accessflow.core.api.ColumnMaskDirective;
import com.bablsoft.accessflow.core.api.DatasourceConnectionDescriptor;
import com.bablsoft.accessflow.core.api.DatasourceConnectionTestException;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.InvalidSqlException;
import com.bablsoft.accessflow.core.api.MaskingStrategy;
import com.bablsoft.accessflow.core.api.QueryDryRunResult;
import com.bablsoft.accessflow.core.api.QueryEngineContext;
import com.bablsoft.accessflow.core.api.QueryEngineDryRunRequest;
import com.bablsoft.accessflow.core.api.QueryEngineExecutionRequest;
import com.bablsoft.accessflow.core.api.QueryEngineSampleRequest;
import com.bablsoft.accessflow.core.api.QueryExecutionRequest;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.RowSecurityDirective;
import com.bablsoft.accessflow.core.api.RowSecurityOperator;
import com.bablsoft.accessflow.core.api.SampleTableRequest;
import com.bablsoft.accessflow.core.api.SelectExecutionResult;
import com.bablsoft.accessflow.core.api.SslMode;
import com.bablsoft.accessflow.core.api.UnrewritableRowSecurityException;
import com.bablsoft.accessflow.core.api.UpdateExecutionResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.neo4j.Neo4jContainer;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Drives the full engine through the {@link Neo4jQueryEngine} SPI facade — the exact surface the
 * host calls after ServiceLoader discovery — against a real {@code neo4j:5} container, proving the
 * AF-423 acceptance behaviour: Cypher read/write/DDL classification and execution; LOAD CSV,
 * non-allow-listed procedure calls, and multi-statement input rejected with 422; WHERE-spliced
 * row-level security provably filtering a policied label (fail-closed on anonymous / write shapes);
 * property masking; schema introspection; connection probing; and eviction.
 */
class Neo4jQueryEngineIntegrationTest {

    private static final String PASSWORD = "test-password";
    private static final String DATABASE = "neo4j";

    static final Neo4jContainer NEO4J = new Neo4jContainer("neo4j:5.26")
            .withAdminPassword(PASSWORD);

    private static Neo4jQueryEngine engine;
    private static DatasourceConnectionDescriptor descriptor;

    @BeforeAll
    static void startContainerAndEngine() {
        NEO4J.start();
        engine = new Neo4jQueryEngine();
        engine.initialize(new QueryEngineContext(
                TestMessages.keyEcho(), ciphertext -> ciphertext, Map.of(), Clock.systemUTC()));
        descriptor = descriptor(NEO4J.getHost(), NEO4J.getMappedPort(7687));
    }

    @AfterAll
    static void stopEngineAndContainer() {
        if (engine != null) {
            engine.shutdown();
        }
        NEO4J.stop();
    }

    @BeforeEach
    void seed() {
        run("MATCH (n) DETACH DELETE n");
        run("CREATE (:User {id: 10, name: 'Ada', email: 'ada@x.io', region: 'EU'})");
        run("CREATE (:User {id: 20, name: 'Bo', email: 'bo@x.io', region: 'EU'})");
        run("CREATE (:User {id: 30, name: 'Cy', email: 'cy@x.io', region: 'US'})");
    }

    @Test
    void engineIdMatchesConnectorId() {
        assertThat(engine.engineId()).isEqualTo("neo4j");
    }

    @Test
    void selectReturnsRows() {
        var result = (SelectExecutionResult) run("MATCH (u:User) RETURN u.name AS name");
        assertThat(result.rowCount()).isEqualTo(3);
        assertThat(result.columns()).extracting("name").containsExactly("name");
        assertThat(result.truncated()).isFalse();
    }

    @Test
    void selectHonoursMaxRowsAndDetectsTruncation() {
        var result = (SelectExecutionResult) execute("MATCH (u:User) RETURN u", QueryType.SELECT, 2,
                List.of(), List.of());
        assertThat(result.rows()).hasSize(2);
        assertThat(result.truncated()).isTrue();
    }

    @Test
    void insertUpdateDeleteReportAffectedRows() {
        assertThat(((UpdateExecutionResult) run("CREATE (:User {id: 99})")).rowsAffected())
                .isGreaterThanOrEqualTo(1);
        assertThat(((UpdateExecutionResult) run("MATCH (u:User {id: 10}) SET u.name = 'Ada2'"))
                .rowsAffected()).isEqualTo(1);
        assertThat(((UpdateExecutionResult) run("MATCH (u:User {id: 30}) DETACH DELETE u"))
                .rowsAffected()).isEqualTo(1);
    }

    @Test
    void ddlReportsZeroAffected() {
        assertThat(((UpdateExecutionResult) run(
                "CREATE INDEX user_id IF NOT EXISTS FOR (u:User) ON (u.id)")).rowsAffected()).isZero();
        assertThat(((UpdateExecutionResult) run("DROP INDEX user_id IF EXISTS")).rowsAffected())
                .isZero();
    }

    @Test
    void loadCsvProcedureAndMultiStatementRejected() {
        assertThatThrownBy(() -> engine.parse("LOAD CSV FROM 'file:///x' AS r RETURN r"))
                .isInstanceOf(InvalidSqlException.class).hasMessageContaining("load_csv");
        assertThatThrownBy(() -> engine.parse("CALL dbms.components() YIELD name RETURN name"))
                .isInstanceOf(InvalidSqlException.class).hasMessageContaining("procedure_forbidden");
        assertThatThrownBy(() -> engine.parse("MATCH (a) RETURN a; MATCH (b) RETURN b"))
                .isInstanceOf(InvalidSqlException.class).hasMessageContaining("multiple_statements");
    }

    @Test
    void rowSecurityPredicateProvablyFiltersExcludedNodes() {
        var directive = new RowSecurityDirective(UUID.randomUUID(), "User", "region",
                RowSecurityOperator.EQUALS, List.of("EU"));
        var result = (SelectExecutionResult) execute("MATCH (u:User) RETURN u.name AS name",
                QueryType.SELECT, 100, List.of(directive), List.of());
        assertThat(result.rowCount()).isEqualTo(2);
        assertThat(result.rows()).extracting(row -> row.get(0))
                .containsExactlyInAnyOrder("Ada", "Bo");
        assertThat(result.appliedRowSecurityPolicyIds()).containsExactly(directive.policyId());
    }

    @Test
    void rowSecurityOnAnonymousNodeFailsClosed() {
        var directive = new RowSecurityDirective(UUID.randomUUID(), "User", "region",
                RowSecurityOperator.EQUALS, List.of("EU"));
        assertThatThrownBy(() -> execute("MATCH (:User) RETURN 1", QueryType.SELECT, 100,
                List.of(directive), List.of()))
                .isInstanceOf(UnrewritableRowSecurityException.class);
    }

    @Test
    void writeCreatingPoliciedLabelIsRejected() {
        var directive = new RowSecurityDirective(UUID.randomUUID(), "User", "region",
                RowSecurityOperator.EQUALS, List.of("EU"));
        assertThatThrownBy(() -> execute("CREATE (:User {id: 1})", QueryType.INSERT, 100,
                List.of(directive), List.of()))
                .isInstanceOf(UnrewritableRowSecurityException.class);
    }

    @Test
    void columnMaskRedactsReturnedProperty() {
        var mask = new ColumnMaskDirective("User.email", MaskingStrategy.EMAIL, Map.of(),
                UUID.randomUUID());
        var result = (SelectExecutionResult) execute("MATCH (u:User) RETURN u", QueryType.SELECT,
                100, List.of(), List.of(mask));
        assertThat(result.rows()).allSatisfy(row -> {
            @SuppressWarnings("unchecked")
            var node = (Map<String, Object>) row.get(0);
            assertThat(String.valueOf(node.get("email"))).contains("***");
        });
        assertThat(result.appliedMaskingPolicyIds()).containsExactly(mask.policyId());
    }

    @Test
    void sampleTableReturnsNodes() {
        var result = sample("User", 100, List.of(), List.of());
        assertThat(result.rowCount()).isEqualTo(3);
        assertThat(result.columns()).extracting(c -> c.name()).contains("n");
    }

    @Test
    void sampleTableHonoursRowCap() {
        var result = sample("User", 2, List.of(), List.of());
        assertThat(result.rows()).hasSize(2);
        assertThat(result.truncated()).isTrue();
    }

    @Test
    void sampleTableAppliesLabelScopedRowSecurity() {
        var directive = new RowSecurityDirective(UUID.randomUUID(), "User", "region",
                RowSecurityOperator.EQUALS, List.of("EU"));
        var result = sample("User", 100, List.of(directive), List.of());
        assertThat(result.rowCount()).isEqualTo(2);
        assertThat(result.appliedRowSecurityPolicyIds()).containsExactly(directive.policyId());
    }

    @Test
    void sampleTableAppliesLabelAwareColumnMask() {
        var mask = new ColumnMaskDirective("User.email", MaskingStrategy.EMAIL, Map.of(),
                UUID.randomUUID());
        var result = sample("User", 100, List.of(), List.of(mask));
        assertThat(result.rows()).allSatisfy(row -> {
            @SuppressWarnings("unchecked")
            var node = (Map<String, Object>) row.get(0);
            assertThat(String.valueOf(node.get("email"))).contains("***");
        });
        assertThat(result.appliedMaskingPolicyIds()).containsExactly(mask.policyId());
    }

    @Test
    void connectionProbeSucceedsAndFailsForUnreachableHost() {
        assertThat(engine.testConnection(descriptor).ok()).isTrue();
        assertThatThrownBy(() -> engine.testConnection(descriptor("127.0.0.1", 1)))
                .isInstanceOf(DatasourceConnectionTestException.class);
    }

    @Test
    void schemaIntrospectionSurfacesLabelsAndProperties() {
        var schema = engine.introspectSchema(descriptor);
        var graph = schema.schemas().stream()
                .filter(s -> s.name().equals(DATABASE)).findFirst().orElseThrow();
        var user = graph.tables().stream()
                .filter(t -> t.name().equals("User")).findFirst().orElseThrow();
        assertThat(user.columns()).extracting("name").contains("_elementId", "email", "region");
        assertThat(user.columns()).filteredOn("primaryKey", true).extracting("name")
                .containsExactly("_elementId");
    }

    @Test
    void evictDatasourceClosesDriverAndQueriesReopenTransparently() {
        run("MATCH (u:User) RETURN u");
        engine.evictDatasource(descriptor.id()); // close branch
        engine.evictDatasource(descriptor.id()); // no-op branch (already gone)
        assertThat(((SelectExecutionResult) run("MATCH (u:User) RETURN u")).rowCount()).isEqualTo(3);
    }

    private static DatasourceConnectionDescriptor descriptor(String host, int port) {
        return new DatasourceConnectionDescriptor(UUID.randomUUID(), UUID.randomUUID(),
                DbType.NEO4J, host, port, DATABASE, "neo4j", PASSWORD, SslMode.DISABLE, 10, 1000,
                true, null, false, null, "neo4j", null, null, null, null, true, null, null);
    }

    @Test
    void dryRunMatchReturnsPlanWithEstimate() {
        var result = dryRun("MATCH (u:User) WHERE u.region = 'EU' RETURN u", List.of());
        assertThat(result.supported()).isTrue();
        assertThat(result.queryType()).isEqualTo(QueryType.SELECT);
        assertThat(result.plan()).isNotNull();
        assertThat(result.plan().operation()).isNotBlank();
    }

    @Test
    void dryRunCreatePlansWithoutMutating() {
        long before = ((SelectExecutionResult) run("MATCH (u:User) RETURN u")).rowCount();
        var result = dryRun("CREATE (:User {id: 777})", List.of());
        assertThat(result.supported()).isTrue();
        assertThat(result.queryType()).isEqualTo(QueryType.INSERT);
        long after = ((SelectExecutionResult) run("MATCH (u:User) RETURN u")).rowCount();
        assertThat(after).isEqualTo(before);
    }

    @Test
    void dryRunSchemaCommandIsUnsupported() {
        var result = dryRun("CREATE INDEX idx_dry IF NOT EXISTS FOR (u:User) ON (u.id)", List.of());
        assertThat(result.supported()).isFalse();
    }

    @Test
    void dryRunAppliesRowSecurity() {
        var directive = new RowSecurityDirective(UUID.randomUUID(), "User", "region",
                RowSecurityOperator.EQUALS, List.of("EU"));
        var result = dryRun("MATCH (u:User) RETURN u", List.of(directive));
        assertThat(result.supported()).isTrue();
        assertThat(result.appliedRowSecurityPolicyIds()).containsExactly(directive.policyId());
    }

    private static QueryDryRunResult dryRun(String query, List<RowSecurityDirective> rls) {
        var type = engine.parse(query).type();
        var request = new QueryExecutionRequest(descriptor.id(), query, type, null, null,
                List.of(), List.of(), rls, false, List.of(query));
        return engine.dryRun(new QueryEngineDryRunRequest(request, descriptor,
                Duration.ofSeconds(30)));
    }

    private static Object run(String query) {
        return execute(query, engine.parse(query).type(), 1000, List.of(), List.of());
    }

    private static SelectExecutionResult sample(String label, int maxRows,
                                                List<RowSecurityDirective> rls,
                                                List<ColumnMaskDirective> masks) {
        var request = new SampleTableRequest(descriptor.id(), descriptor.databaseName(), label,
                List.of(), masks, rls, null, null);
        return (SelectExecutionResult) engine.sampleTable(new QueryEngineSampleRequest(request,
                descriptor, maxRows, Duration.ofSeconds(30)));
    }

    private static Object execute(String query, QueryType type, int maxRows,
                                  List<RowSecurityDirective> rls, List<ColumnMaskDirective> masks) {
        var request = new QueryExecutionRequest(descriptor.id(), query, type, null, null,
                List.of(), masks, rls, false, List.of(query));
        return engine.execute(new QueryEngineExecutionRequest(request, descriptor, maxRows,
                Duration.ofSeconds(30)));
    }
}
