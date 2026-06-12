package com.bablsoft.accessflow.engine.cassandra;

import com.bablsoft.accessflow.core.api.ColumnMaskDirective;
import com.bablsoft.accessflow.core.api.DatasourceConnectionDescriptor;
import com.bablsoft.accessflow.core.api.DatasourceConnectionTestException;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.InvalidSqlException;
import com.bablsoft.accessflow.core.api.MaskingStrategy;
import com.bablsoft.accessflow.core.api.QueryEngineContext;
import com.bablsoft.accessflow.core.api.QueryEngineExecutionRequest;
import com.bablsoft.accessflow.core.api.QueryExecutionRequest;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.RowSecurityDirective;
import com.bablsoft.accessflow.core.api.RowSecurityOperator;
import com.bablsoft.accessflow.core.api.SelectExecutionResult;
import com.bablsoft.accessflow.core.api.SslMode;
import com.bablsoft.accessflow.core.api.UnrewritableRowSecurityException;
import com.bablsoft.accessflow.core.api.UpdateExecutionResult;
import com.datastax.oss.driver.api.core.CqlSession;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.cassandra.CassandraContainer;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Drives the full engine through the {@link CassandraQueryEngine} SPI facade — the exact surface
 * the host calls after ServiceLoader discovery — against a real {@code cassandra:5} container,
 * proving the AF-421 acceptance behaviour: CQL DML/DDL classification and execution, BATCH and
 * UDF/UDA rejection, key-aware WHERE-spliced row-level security (non-key policies fail closed),
 * field masking, schema introspection, connection probing, and eviction. The fixture table
 * {@code app.users} has a composite primary key {@code (tenant_id, id)}.
 */
class CassandraQueryEngineIntegrationTest {

    private static final String KEYSPACE = "app";

    static final CassandraContainer CASSANDRA = new CassandraContainer("cassandra:5");

    private static CassandraQueryEngine engine;
    private static DatasourceConnectionDescriptor descriptor;

    @BeforeAll
    static void startContainerAndEngine() {
        CASSANDRA.start();
        try (var admin = CqlSession.builder()
                .addContactPoint(CASSANDRA.getContactPoint())
                .withLocalDatacenter(CASSANDRA.getLocalDatacenter())
                .build()) {
            admin.execute("CREATE KEYSPACE IF NOT EXISTS app WITH replication = "
                    + "{'class':'SimpleStrategy','replication_factor':1}");
            admin.execute("CREATE TABLE IF NOT EXISTS app.users (tenant_id int, id int, "
                    + "name text, email text, PRIMARY KEY (tenant_id, id))");
        }
        engine = new CassandraQueryEngine();
        engine.initialize(new QueryEngineContext(
                TestMessages.keyEcho(), ciphertext -> ciphertext, Map.of(), Clock.systemUTC()));
        var contact = CASSANDRA.getContactPoint();
        descriptor = new DatasourceConnectionDescriptor(UUID.randomUUID(), UUID.randomUUID(),
                DbType.CASSANDRA, contact.getHostString(), contact.getPort(), KEYSPACE, null, "",
                SslMode.DISABLE, 10, 1000, true, null, false, null, "cassandra", null, null, null,
                null, true, CASSANDRA.getLocalDatacenter());
    }

    @AfterAll
    static void stopEngineAndContainer() {
        if (engine != null) {
            engine.shutdown();
        }
        CASSANDRA.stop();
    }

    @BeforeEach
    void seed() {
        run("TRUNCATE users");
        run("INSERT INTO users (tenant_id, id, name, email) VALUES (1, 10, 'Ada', 'ada@x.io')");
        run("INSERT INTO users (tenant_id, id, name, email) VALUES (1, 20, 'Bo', 'bo@x.io')");
        run("INSERT INTO users (tenant_id, id, name, email) VALUES (2, 30, 'Cy', 'cy@x.io')");
    }

    @Test
    void engineIdMatchesConnectorId() {
        assertThat(engine.engineId()).isEqualTo("cassandra");
    }

    @Test
    void insertUpdateDeleteReportAffectedRows() {
        assertThat(((UpdateExecutionResult) run(
                "INSERT INTO users (tenant_id, id, name) VALUES (3, 40, 'Di')")).rowsAffected())
                .isEqualTo(1);
        assertThat(((UpdateExecutionResult) run(
                "UPDATE users SET name = 'Ada2' WHERE tenant_id = 1 AND id = 10")).rowsAffected())
                .isEqualTo(1);
        assertThat(((UpdateExecutionResult) run(
                "DELETE FROM users WHERE tenant_id = 2 AND id = 30")).rowsAffected()).isEqualTo(1);
    }

    @Test
    void selectReturnsColumnsAndRows() {
        var result = (SelectExecutionResult) run("SELECT * FROM users WHERE tenant_id = 1");
        assertThat(result.rowCount()).isEqualTo(2);
        assertThat(result.columns()).extracting("name").contains("tenant_id", "id", "name", "email");
        assertThat(result.truncated()).isFalse();
    }

    @Test
    void selectHonoursMaxRowsAndDetectsTruncation() {
        var result = (SelectExecutionResult) execute("SELECT * FROM users", QueryType.SELECT, 2,
                List.of(), List.of());
        assertThat(result.rows()).hasSize(2);
        assertThat(result.truncated()).isTrue();
    }

    @Test
    void ddlReportsZeroAffected() {
        assertThat(((UpdateExecutionResult) run("CREATE INDEX ix_email ON users (email)"))
                .rowsAffected()).isZero();
        assertThat(((UpdateExecutionResult) run("DROP INDEX ix_email")).rowsAffected()).isZero();
        assertThat(((UpdateExecutionResult) run("TRUNCATE users")).rowsAffected()).isZero();
    }

    @Test
    void batchAndUdfAreRejectedWithDistinctMessages() {
        assertThatThrownBy(() -> engine.parse(
                "BEGIN BATCH INSERT INTO users (tenant_id, id) VALUES (9, 1); APPLY BATCH"))
                .isInstanceOf(InvalidSqlException.class)
                .hasMessageContaining("error.cassandra.batch_forbidden");
        assertThatThrownBy(() -> engine.parse("CREATE FUNCTION f(i int) "
                + "RETURNS NULL ON NULL INPUT RETURNS int LANGUAGE java AS 'return i;'"))
                .isInstanceOf(InvalidSqlException.class)
                .hasMessageContaining("error.cassandra.udf_forbidden");
    }

    @Test
    void rowSecurityOnPartitionKeyFiltersRows() {
        var directive = new RowSecurityDirective(UUID.randomUUID(), "users", "tenant_id",
                RowSecurityOperator.EQUALS, List.of(2));
        var result = (SelectExecutionResult) execute("SELECT * FROM users", QueryType.SELECT, 100,
                List.of(directive), List.of());
        assertThat(result.rowCount()).isEqualTo(1);
        assertThat(result.rows().get(0)).contains("Cy");
        assertThat(result.appliedRowSecurityPolicyIds()).containsExactly(directive.policyId());
    }

    @Test
    void rowSecurityOnNonKeyColumnFailsClosed() {
        var directive = new RowSecurityDirective(UUID.randomUUID(), "users", "email",
                RowSecurityOperator.EQUALS, List.of("ada@x.io"));
        assertThatThrownBy(() -> execute("SELECT * FROM users", QueryType.SELECT, 100,
                List.of(directive), List.of()))
                .isInstanceOf(UnrewritableRowSecurityException.class)
                .hasMessageContaining("error.row_security_cassandra_unrewritable");
    }

    @Test
    void rowSecurityWithUnsupportedOperatorFailsClosed() {
        var directive = new RowSecurityDirective(UUID.randomUUID(), "users", "tenant_id",
                RowSecurityOperator.NOT_EQUALS, List.of(2));
        assertThatThrownBy(() -> execute("SELECT * FROM users", QueryType.SELECT, 100,
                List.of(directive), List.of()))
                .isInstanceOf(UnrewritableRowSecurityException.class);
    }

    @Test
    void insertIntoPoliciedTableIsRejected() {
        var directive = new RowSecurityDirective(UUID.randomUUID(), "users", "tenant_id",
                RowSecurityOperator.EQUALS, List.of(1));
        assertThatThrownBy(() -> execute(
                "INSERT INTO users (tenant_id, id) VALUES (1, 99)", QueryType.INSERT, 100,
                List.of(directive), List.of()))
                .isInstanceOf(UnrewritableRowSecurityException.class);
    }

    @Test
    void columnMaskRedactsFields() {
        var mask = new ColumnMaskDirective("users.email", MaskingStrategy.EMAIL, Map.of(),
                UUID.randomUUID());
        var result = (SelectExecutionResult) execute("SELECT * FROM users WHERE tenant_id = 1",
                QueryType.SELECT, 100, List.of(), List.of(mask));
        int emailIdx = result.columns().stream().map(c -> c.name()).toList().indexOf("email");
        assertThat(emailIdx).isNotNegative();
        assertThat(result.rows()).allMatch(row -> String.valueOf(row.get(emailIdx)).contains("***"));
        assertThat(result.appliedMaskingPolicyIds()).containsExactly(mask.policyId());
    }

    @Test
    void connectionProbeSucceeds() {
        assertThat(engine.testConnection(descriptor).ok()).isTrue();
    }

    @Test
    void connectionProbeFailsForUnreachableHost() {
        assertThatThrownBy(() -> engine.testConnection(unreachableDescriptor()))
                .isInstanceOf(DatasourceConnectionTestException.class);
    }

    @Test
    void schemaIntrospectionListsKeyspacesTablesAndFlagsPrimaryKeys() {
        var schema = engine.introspectSchema(descriptor);
        var keyspace = schema.schemas().stream()
                .filter(s -> s.name().equals(KEYSPACE)).findFirst().orElseThrow();
        var table = keyspace.tables().stream()
                .filter(t -> t.name().equals("users")).findFirst().orElseThrow();
        assertThat(table.columns()).extracting("name")
                .contains("tenant_id", "id", "name", "email");
        assertThat(table.columns()).filteredOn("primaryKey", true).extracting("name")
                .containsExactlyInAnyOrder("tenant_id", "id");
    }

    @Test
    void schemaIntrospectionFailsForUnreachableHost() {
        assertThatThrownBy(() -> engine.introspectSchema(unreachableDescriptor()))
                .isInstanceOf(DatasourceConnectionTestException.class);
    }

    @Test
    void evictDatasourceClosesSessionAndQueriesReopenTransparently() {
        run("SELECT * FROM users WHERE tenant_id = 1");
        engine.evictDatasource(descriptor.id()); // close branch
        engine.evictDatasource(descriptor.id()); // no-op branch (already gone)
        assertThat(((SelectExecutionResult) run("SELECT * FROM users")).rowCount()).isEqualTo(3);
    }

    private static DatasourceConnectionDescriptor unreachableDescriptor() {
        return new DatasourceConnectionDescriptor(UUID.randomUUID(), UUID.randomUUID(),
                DbType.CASSANDRA, "127.0.0.1", 1, KEYSPACE, null, "", SslMode.DISABLE, 10, 1000,
                true, null, false, null, "cassandra", null, null, null, null, true, "datacenter1");
    }

    private static Object run(String query) {
        return execute(query, engine.parse(query).type(), 1000, List.of(), List.of());
    }

    private static Object execute(String query, QueryType type, int maxRows,
                                  List<RowSecurityDirective> rls, List<ColumnMaskDirective> masks) {
        var request = new QueryExecutionRequest(descriptor.id(), query, type, null, null,
                List.of(), masks, rls, false, List.of(query));
        return engine.execute(new QueryEngineExecutionRequest(request, descriptor, maxRows,
                Duration.ofSeconds(30)));
    }
}
