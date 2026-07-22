package com.bablsoft.accessflow.engine.bigquery;

import com.bablsoft.accessflow.core.api.ColumnMaskDirective;
import com.bablsoft.accessflow.core.api.DatasourceConnectionDescriptor;
import com.bablsoft.accessflow.core.api.DatasourceConnectionTestException;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.InvalidSqlException;
import com.bablsoft.accessflow.core.api.MaskingStrategy;
import com.bablsoft.accessflow.core.api.QueryEngineContext;
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
import com.google.cloud.NoCredentials;
import com.google.cloud.bigquery.BigQuery;
import com.google.cloud.bigquery.BigQueryOptions;
import com.google.cloud.bigquery.QueryJobConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Drives the full engine through the {@link BigQueryQueryEngine} SPI facade — the exact surface
 * the host calls after ServiceLoader discovery — against a real
 * {@code ghcr.io/goccy/bigquery-emulator} container, proving the acceptance behaviour: GoogleSQL
 * CRUD + DDL classification and execution, scripting rejection, WHERE-spliced row-level security
 * (incl. deny-all, INSERT-into-policied rejection, and JOIN fail-closed), column masking (flat +
 * nested RECORD dot-path), truncation at the row cap, table sampling, schema introspection,
 * connection probing, and eviction. The fixture table {@code dataset1.users} has columns
 * {@code id}, {@code tenant}, {@code name}, {@code email}, and a STRUCT {@code profile}
 * ({@code ssn}, {@code phone}).
 */
class BigQueryQueryEngineIntegrationTest {

    private static final String PROJECT = "test";
    private static final String DATASET = "dataset1";

    static final GenericContainer<?> BIGQUERY = new GenericContainer<>(
            DockerImageName.parse("ghcr.io/goccy/bigquery-emulator:latest"))
            .withCommand("--project=" + PROJECT, "--dataset=" + DATASET)
            .withExposedPorts(9050);

    private static BigQueryQueryEngine engine;
    private static DatasourceConnectionDescriptor descriptor;
    private static BigQuery adminClient;

    @BeforeAll
    static void startContainerAndEngine() throws Exception {
        BIGQUERY.start();
        var endpoint = "http://" + BIGQUERY.getHost() + ":" + BIGQUERY.getMappedPort(9050);
        adminClient = BigQueryOptions.newBuilder()
                .setProjectId(PROJECT)
                .setHost(endpoint)
                .setCredentials(NoCredentials.getInstance())
                .build()
                .getService();
        adminQuery("CREATE TABLE " + DATASET + ".users ("
                + "id INT64, tenant STRING, name STRING, email STRING, "
                + "profile STRUCT<ssn STRING, phone STRING>)");
        adminQuery("INSERT INTO " + DATASET + ".users (id, tenant, name, email, profile) VALUES "
                + "(1, 'acme', 'Ada', 'ada@x.io', STRUCT('111-11-1111', '555-0100')), "
                + "(2, 'acme', 'Bo', 'bo@x.io', STRUCT('222-22-2222', '555-0200')), "
                + "(3, 'globex', 'Cy', 'cy@x.io', STRUCT('333-33-3333', '555-0300'))");

        engine = new BigQueryQueryEngine();
        engine.initialize(new QueryEngineContext(
                TestMessages.keyEcho(), ciphertext -> ciphertext, Map.of(), Clock.systemUTC()));
        descriptor = descriptor(PROJECT + "." + DATASET, endpoint);
    }

    @AfterAll
    static void stopEngineAndContainer() {
        if (engine != null) {
            engine.shutdown();
        }
        BIGQUERY.stop();
    }

    @Test
    void engineIdMatchesConnectorId() {
        assertThat(engine.engineId()).isEqualTo("bigquery");
    }

    @Test
    void parsesGoogleSqlAndRejectsScripting() {
        assertThat(engine.parse("SELECT * FROM " + DATASET + ".users").type())
                .isEqualTo(QueryType.SELECT);
        assertThat(engine.parse("CREATE TABLE " + DATASET + ".x (id INT64)").type())
                .isEqualTo(QueryType.DDL);
        assertThatThrownBy(() -> engine.parse("EXECUTE IMMEDIATE 'SELECT 1'"))
                .isInstanceOf(InvalidSqlException.class);
        assertThatThrownBy(() -> engine.parse("SELECT 1; SELECT 2"))
                .isInstanceOf(InvalidSqlException.class);
    }

    @Test
    void selectReturnsColumnsAndRows() {
        var result = (SelectExecutionResult) run("SELECT * FROM " + DATASET + ".users ORDER BY id");
        assertThat(result.rowCount()).isEqualTo(3);
        assertThat(result.columns()).extracting("name")
                .contains("id", "tenant", "name", "email", "profile");
    }

    @Test
    void insertUpdateDeleteRoundTrip() {
        // The goccy emulator never reports numDmlAffectedRows (real BigQuery does, through the
        // same QueryStatistics path), so DML is verified by reading its effect back.
        assertThat(run("INSERT INTO " + DATASET + ".users (id, tenant, name) VALUES (9, 'acme', 'Di')"))
                .isInstanceOf(UpdateExecutionResult.class);
        assertThat(names("SELECT name FROM " + DATASET + ".users WHERE id = 9"))
                .containsExactly("Di");
        run("UPDATE " + DATASET + ".users SET name = 'Di2' WHERE id = 9");
        assertThat(names("SELECT name FROM " + DATASET + ".users WHERE id = 9"))
                .containsExactly("Di2");
        run("DELETE FROM " + DATASET + ".users WHERE id = 9");
        assertThat(names("SELECT name FROM " + DATASET + ".users WHERE id = 9")).isEmpty();
    }

    @Test
    void selectHonoursMaxRowsAndDetectsTruncation() {
        var result = (SelectExecutionResult) execute("SELECT * FROM " + DATASET + ".users",
                QueryType.SELECT, 2, List.of(), List.of());
        assertThat(result.rows()).hasSize(2);
        assertThat(result.truncated()).isTrue();
    }

    @Test
    void rowSecurityFiltersRows() {
        var directive = new RowSecurityDirective(UUID.randomUUID(), "users", "tenant",
                RowSecurityOperator.EQUALS, List.of("globex"));
        var result = (SelectExecutionResult) execute("SELECT * FROM " + DATASET + ".users",
                QueryType.SELECT, 100, List.of(directive), List.of());
        assertThat(result.rowCount()).isEqualTo(1);
        assertThat(result.appliedRowSecurityPolicyIds()).containsExactly(directive.policyId());
    }

    @Test
    void rowSecurityInDirectiveBindsEveryValue() {
        var directive = new RowSecurityDirective(UUID.randomUUID(), "users", "tenant",
                RowSecurityOperator.IN, List.of("acme", "initech"));
        var result = (SelectExecutionResult) execute("SELECT * FROM " + DATASET + ".users",
                QueryType.SELECT, 100, List.of(directive), List.of());
        assertThat(result.rowCount()).isEqualTo(2);
    }

    @Test
    void rowSecurityDenyAllReturnsNothingWithoutExecuting() {
        var directive = new RowSecurityDirective(UUID.randomUUID(), "users", "tenant",
                RowSecurityOperator.EQUALS, List.of());
        var result = (SelectExecutionResult) execute("SELECT * FROM " + DATASET + ".users",
                QueryType.SELECT, 100, List.of(directive), List.of());
        assertThat(result.rowCount()).isZero();
        assertThat(result.appliedRowSecurityPolicyIds()).containsExactly(directive.policyId());
    }

    @Test
    void insertIntoPoliciedTableIsRejected() {
        var directive = new RowSecurityDirective(UUID.randomUUID(), "users", "tenant",
                RowSecurityOperator.EQUALS, List.of("acme"));
        assertThatThrownBy(() -> execute(
                "INSERT INTO " + DATASET + ".users (id, tenant) VALUES (99, 'acme')",
                QueryType.INSERT, 100, List.of(directive), List.of()))
                .isInstanceOf(UnrewritableRowSecurityException.class);
    }

    @Test
    void joinAgainstPoliciedTableFailsClosed() {
        var directive = new RowSecurityDirective(UUID.randomUUID(), "users", "tenant",
                RowSecurityOperator.EQUALS, List.of("acme"));
        assertThatThrownBy(() -> execute(
                "SELECT * FROM " + DATASET + ".users u JOIN " + DATASET + ".users o ON u.id = o.id",
                QueryType.SELECT, 100, List.of(directive), List.of()))
                .isInstanceOf(UnrewritableRowSecurityException.class);
    }

    @Test
    void columnMaskRedactsTopLevelAndNestedAttributes() {
        var emailMask = new ColumnMaskDirective("email", MaskingStrategy.EMAIL, Map.of(),
                UUID.randomUUID());
        var ssnMask = new ColumnMaskDirective("profile.ssn", MaskingStrategy.FULL, Map.of(),
                UUID.randomUUID());
        var result = (SelectExecutionResult) execute("SELECT * FROM " + DATASET + ".users",
                QueryType.SELECT, 100, List.of(), List.of(emailMask, ssnMask));
        int emailIdx = columnIndex(result, "email");
        int profileIdx = columnIndex(result, "profile");
        assertThat(result.rows()).allMatch(row -> String.valueOf(row.get(emailIdx)).contains("***"));
        assertThat(result.rows()).allMatch(row -> {
            @SuppressWarnings("unchecked")
            var profile = (Map<String, Object>) row.get(profileIdx);
            return "***".equals(profile.get("ssn")) && !"***".equals(profile.get("phone"));
        });
        assertThat(result.appliedMaskingPolicyIds())
                .contains(emailMask.policyId(), ssnMask.policyId());
    }

    @Test
    void sampleTableReturnsGovernedRows() {
        var plain = sample("users", 100, List.of(), List.of());
        assertThat(plain.rowCount()).isEqualTo(3);
        assertThat(plain.columns()).extracting("name").contains("id", "tenant", "name", "email");

        var capped = sample("users", 2, List.of(), List.of());
        assertThat(capped.rows()).hasSize(2);
        assertThat(capped.truncated()).isTrue();

        var directive = new RowSecurityDirective(UUID.randomUUID(), "users", "tenant",
                RowSecurityOperator.EQUALS, List.of("globex"));
        var filtered = sample("users", 100, List.of(directive), List.of());
        assertThat(filtered.rowCount()).isEqualTo(1);
        assertThat(filtered.appliedRowSecurityPolicyIds()).containsExactly(directive.policyId());

        var ssnMask = new ColumnMaskDirective("profile.ssn", MaskingStrategy.FULL, Map.of(),
                UUID.randomUUID());
        var masked = sample("users", 100, List.of(), List.of(ssnMask));
        int profileIdx = columnIndex(masked, "profile");
        assertThat(masked.rows()).allMatch(row -> {
            @SuppressWarnings("unchecked")
            var profile = (Map<String, Object>) row.get(profileIdx);
            return "***".equals(profile.get("ssn")) && !"***".equals(profile.get("phone"));
        });
        assertThat(masked.appliedMaskingPolicyIds()).contains(ssnMask.policyId());
    }

    @Test
    void createAndDropTableViaDdl() {
        assertThat(((UpdateExecutionResult) run(
                "CREATE TABLE " + DATASET + ".widgets (id INT64, label STRING)")).rowsAffected())
                .isZero();
        run("INSERT INTO " + DATASET + ".widgets (id, label) VALUES (1, 'w')");
        assertThat(((SelectExecutionResult) run("SELECT * FROM " + DATASET + ".widgets"))
                .rowCount()).isEqualTo(1);
        assertThat(((UpdateExecutionResult) run("DROP TABLE " + DATASET + ".widgets"))
                .rowsAffected()).isZero();
    }

    @Test
    void connectionProbeSucceedsAndFailsForUnreachableEndpoint() {
        assertThat(engine.testConnection(descriptor).ok()).isTrue();
        assertThatThrownBy(() -> engine.testConnection(
                descriptor(PROJECT + "." + DATASET, "http://127.0.0.1:1")))
                .isInstanceOf(DatasourceConnectionTestException.class);
    }

    @Test
    void schemaIntrospectionFlattensRecordFieldsIntoDotPaths() {
        var schema = engine.introspectSchema(descriptor);
        var dataset = schema.schemas().stream()
                .filter(s -> s.name().equals(DATASET)).findFirst().orElseThrow();
        var users = dataset.tables().stream()
                .filter(t -> t.name().equals("users")).findFirst().orElseThrow();
        assertThat(users.columns()).extracting("name")
                .contains("id", "tenant", "name", "email", "profile.ssn", "profile.phone");
        assertThat(users.columns()).allMatch(c -> !c.primaryKey());
    }

    @Test
    void evictDatasourceDropsClientAndQueriesReopenTransparently() {
        run("SELECT * FROM " + DATASET + ".users");
        engine.evictDatasource(descriptor.id()); // drop branch
        engine.evictDatasource(descriptor.id()); // no-op branch (already gone)
        assertThat(((SelectExecutionResult) run("SELECT * FROM " + DATASET + ".users")).rowCount())
                .isEqualTo(3);
    }

    // ---- helpers ------------------------------------------------------------------------------

    private static void adminQuery(String sql) throws InterruptedException {
        adminClient.query(QueryJobConfiguration.newBuilder(sql).setUseLegacySql(false).build());
    }

    private static List<String> names(String sql) {
        var result = (SelectExecutionResult) run(sql);
        return result.rows().stream().map(row -> String.valueOf(row.get(0))).toList();
    }

    private static int columnIndex(SelectExecutionResult result, String name) {
        var names = result.columns().stream().map(c -> c.name()).toList();
        return names.indexOf(name);
    }

    private static DatasourceConnectionDescriptor descriptor(String databaseName, String endpoint) {
        return new DatasourceConnectionDescriptor(UUID.randomUUID(), UUID.randomUUID(),
                DbType.BIGQUERY, null, null, databaseName, null, "unused-cipher", SslMode.DISABLE,
                10, 1000, true, null, false, null, "bigquery", endpoint, null, null, null, true,
                null);
    }

    private static Object run(String query) {
        return execute(query, engine.parse(query).type(), 1000, List.of(), List.of());
    }

    private static SelectExecutionResult sample(String table, int maxRows,
                                                List<RowSecurityDirective> rls,
                                                List<ColumnMaskDirective> masks) {
        var request = new SampleTableRequest(descriptor.id(), DATASET, table,
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
