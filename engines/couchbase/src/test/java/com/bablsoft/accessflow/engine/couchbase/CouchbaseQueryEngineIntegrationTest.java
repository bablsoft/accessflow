package com.bablsoft.accessflow.engine.couchbase;

import com.bablsoft.accessflow.core.api.ColumnMaskDirective;
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
import org.testcontainers.couchbase.BucketDefinition;
import org.testcontainers.couchbase.CouchbaseContainer;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Drives the full engine through the {@link CouchbaseQueryEngine} SPI facade — the exact surface
 * the host calls after ServiceLoader discovery — against a real Couchbase Server community
 * container, proving the AF-412 acceptance behaviour: SQL++ CRUD/DDL classification and
 * execution, WHERE-spliced row-level security with named parameters, field masking, scope/
 * collection introspection, connection probing, and eviction. All statements run against the
 * bucket's default scope; documents live in the {@code _default} collection (covered by the
 * container-provisioned primary index).
 */
class CouchbaseQueryEngineIntegrationTest {

    private static final String BUCKET = "itest";

    static final CouchbaseContainer COUCHBASE =
            new CouchbaseContainer("couchbase/server:community-7.6.2")
                    .withBucket(new BucketDefinition(BUCKET).withPrimaryIndex(true));

    private static CouchbaseQueryEngine engine;
    private static DatasourceConnectionDescriptor descriptor;

    @BeforeAll
    static void startContainerAndEngine() {
        COUCHBASE.start();
        engine = new CouchbaseQueryEngine();
        engine.initialize(new QueryEngineContext(
                TestMessages.keyEcho(), ciphertext -> ciphertext, Map.of(), Clock.systemUTC()));
        descriptor = new DatasourceConnectionDescriptor(UUID.randomUUID(), UUID.randomUUID(),
                DbType.COUCHBASE, null, null, BUCKET, COUCHBASE.getUsername(),
                COUCHBASE.getPassword(), SslMode.DISABLE, 10, 1000, true, null, false, null,
                "couchbase", COUCHBASE.getConnectionString(), null, null, null, true);
    }

    @AfterAll
    static void stopEngineAndContainer() {
        if (engine != null) {
            engine.shutdown();
        }
        COUCHBASE.stop();
    }

    @BeforeEach
    void seed() {
        run("DELETE FROM _default");
        run("""
                INSERT INTO _default (KEY, VALUE)
                VALUES ('p1', {"name": "Ada", "team": "eng", "salary": 100, "email": "ada@x.io"}),
                       ('p2', {"name": "Bo", "team": "eng", "salary": 90, "email": "bo@x.io"}),
                       ('p3', {"name": "Cy", "team": "sales", "salary": 80, "email": "cy@x.io"})
                """);
    }

    @Test
    void engineIdMatchesConnectorId() {
        assertThat(engine.engineId()).isEqualTo("couchbase");
    }

    @Test
    void insertReportsMutationCount() {
        var result = run("INSERT INTO _default (KEY, VALUE) VALUES ('t1', {\"a\": 1}), "
                + "('t2', {\"a\": 2})");
        assertThat(result).isInstanceOf(UpdateExecutionResult.class);
        assertThat(((UpdateExecutionResult) result).rowsAffected()).isEqualTo(2);
    }

    @Test
    void upsertReportsMutationCount() {
        assertThat(((UpdateExecutionResult) run(
                "UPSERT INTO _default (KEY, VALUE) VALUES ('p1', {\"name\": \"Ada2\"})"))
                .rowsAffected()).isEqualTo(1);
    }

    @Test
    void selectReturnsUnwrappedDocumentsAsColumnsAndRows() {
        var result = (SelectExecutionResult) run(
                "SELECT * FROM _default WHERE team = 'eng' ORDER BY name");
        assertThat(result.rowCount()).isEqualTo(2);
        assertThat(result.columns()).extracting("name").contains("name", "team", "salary");
        assertThat(result.truncated()).isFalse();
    }

    @Test
    void selectProjectionAndRawWork() {
        var projection = (SelectExecutionResult) run(
                "SELECT name, salary FROM _default WHERE team = 'eng' ORDER BY salary DESC");
        assertThat(projection.columns()).extracting("name").containsExactly("name", "salary");
        assertThat(projection.rows().get(0)).containsExactly("Ada", 100);

        var raw = (SelectExecutionResult) run("SELECT RAW name FROM _default ORDER BY name");
        assertThat(raw.columns()).extracting("name").containsExactly("value");
        assertThat(raw.rows()).extracting(r -> r.get(0)).containsExactly("Ada", "Bo", "Cy");
    }

    @Test
    void selectHonoursMaxRowsAndDetectsTruncation() {
        var result = (SelectExecutionResult) execute("SELECT * FROM _default", QueryType.SELECT,
                2, List.of(), List.of());
        assertThat(result.rows()).hasSize(2);
        assertThat(result.truncated()).isTrue();
    }

    @Test
    void aggregateGroups() {
        var result = (SelectExecutionResult) run(
                "SELECT team, SUM(salary) AS total FROM _default GROUP BY team");
        assertThat(result.rowCount()).isEqualTo(2);
        assertThat(result.columns()).extracting("name").containsExactlyInAnyOrder("team", "total");
    }

    @Test
    void updateAndDeleteReportMutationCounts() {
        assertThat(((UpdateExecutionResult) run(
                "UPDATE _default SET bonus = 1 WHERE team = 'eng'")).rowsAffected()).isEqualTo(2);
        assertThat(((UpdateExecutionResult) run(
                "DELETE FROM _default WHERE name = 'Cy'")).rowsAffected()).isEqualTo(1);
    }

    @Test
    void mergeReportsMutationCount() {
        var result = run("""
                MERGE INTO _default AS t
                USING [{"name": "Ada", "salary": 120}] AS src
                ON t.name = src.name
                WHEN MATCHED THEN UPDATE SET t.salary = src.salary
                """);
        assertThat(((UpdateExecutionResult) result).rowsAffected()).isEqualTo(1);
    }

    @Test
    void ddlCreateAndDropIndexAndCollection() {
        assertThat(((UpdateExecutionResult) run("CREATE INDEX idx_team ON _default(team)"))
                .rowsAffected()).isZero();
        assertThat(((UpdateExecutionResult) run("DROP INDEX idx_team ON _default"))
                .rowsAffected()).isZero();
        assertThat(((UpdateExecutionResult) run("CREATE COLLECTION people")).rowsAffected())
                .isZero();
        assertThat(((UpdateExecutionResult) run("DROP COLLECTION people")).rowsAffected())
                .isZero();
    }

    @Test
    void rowSecurityFiltersSelectedRows() {
        var directive = new RowSecurityDirective(UUID.randomUUID(), "_default", "team",
                RowSecurityOperator.EQUALS, List.of("sales"));
        var result = (SelectExecutionResult) execute("SELECT * FROM _default", QueryType.SELECT,
                100, List.of(directive), List.of());
        assertThat(result.rowCount()).isEqualTo(1);
        assertThat(result.rows().get(0)).contains("Cy");
        assertThat(result.appliedRowSecurityPolicyIds()).containsExactly(directive.policyId());
    }

    @Test
    void rowSecurityBoundsUpdatesAndDeletes() {
        var directive = new RowSecurityDirective(UUID.randomUUID(), "_default", "team",
                RowSecurityOperator.EQUALS, List.of("eng"));
        var update = (UpdateExecutionResult) execute(
                "UPDATE _default SET bonus = 1", QueryType.UPDATE, 100, List.of(directive),
                List.of());
        assertThat(update.rowsAffected()).isEqualTo(2);
        assertThat(update.appliedRowSecurityPolicyIds()).containsExactly(directive.policyId());

        var delete = (UpdateExecutionResult) execute(
                "DELETE FROM _default WHERE salary < 95", QueryType.DELETE, 100,
                List.of(directive), List.of());
        // Cy (sales, 80) is shielded by the eng-only predicate; only Bo (eng, 90) matches.
        assertThat(delete.rowsAffected()).isEqualTo(1);
    }

    @Test
    void rowSecurityWithEmptyValuesIsDenyAll() {
        var directive = new RowSecurityDirective(UUID.randomUUID(), "_default", "team",
                RowSecurityOperator.EQUALS, List.of());
        var result = (SelectExecutionResult) execute("SELECT * FROM _default", QueryType.SELECT,
                100, List.of(directive), List.of());
        assertThat(result.rowCount()).isZero();
    }

    @Test
    void insertIntoAPoliciedKeyspaceIsRejected() {
        var directive = new RowSecurityDirective(UUID.randomUUID(), "_default", "team",
                RowSecurityOperator.EQUALS, List.of("eng"));
        assertThatThrownBy(() -> execute(
                "INSERT INTO _default (KEY, VALUE) VALUES ('x', {\"a\": 1})", QueryType.INSERT,
                100, List.of(directive), List.of()))
                .isInstanceOf(UnrewritableRowSecurityException.class);
    }

    @Test
    void policiedMergeFailsClosed() {
        var directive = new RowSecurityDirective(UUID.randomUUID(), "_default", "team",
                RowSecurityOperator.EQUALS, List.of("eng"));
        assertThatThrownBy(() -> execute("""
                MERGE INTO _default AS t USING (SELECT 1 AS x) AS s ON t.name = 'Ada'
                WHEN MATCHED THEN UPDATE SET t.touched = true
                """, QueryType.UPDATE, 100, List.of(directive), List.of()))
                .isInstanceOf(UnrewritableRowSecurityException.class);
    }

    @Test
    void columnMaskRedactsFieldsIncludingSelectStar() {
        var mask = new ColumnMaskDirective("_default.email", MaskingStrategy.EMAIL, Map.of(),
                UUID.randomUUID());
        var result = (SelectExecutionResult) execute(
                "SELECT * FROM _default WHERE name = 'Ada'", QueryType.SELECT, 100, List.of(),
                List.of(mask));
        int emailIdx = result.columns().stream().map(c -> c.name()).toList().indexOf("email");
        assertThat(emailIdx).isNotNegative();
        assertThat(result.rows().get(0).get(emailIdx)).isEqualTo("a***@x.io");
        assertThat(result.appliedMaskingPolicyIds()).containsExactly(mask.policyId());
    }

    @Test
    void sampleTableReturnsCollectionDocuments() {
        var result = sample("_default", 100, List.of(), List.of());
        assertThat(result.rowCount()).isEqualTo(3);
        assertThat(result.columns()).extracting("name").contains("name", "team", "email");
    }

    @Test
    void sampleTableHonoursRowCap() {
        var result = sample("_default", 2, List.of(), List.of());
        assertThat(result.rows()).hasSize(2);
        assertThat(result.truncated()).isTrue();
    }

    @Test
    void sampleTableAppliesRowSecurity() {
        var directive = new RowSecurityDirective(UUID.randomUUID(), "_default", "team",
                RowSecurityOperator.EQUALS, List.of("sales"));
        var result = sample("_default", 100, List.of(directive), List.of());
        assertThat(result.rowCount()).isEqualTo(1);
        assertThat(result.appliedRowSecurityPolicyIds()).containsExactly(directive.policyId());
    }

    @Test
    void sampleTableAppliesColumnMask() {
        var mask = new ColumnMaskDirective("_default.email", MaskingStrategy.EMAIL, Map.of(),
                UUID.randomUUID());
        var result = sample("_default", 100, List.of(), List.of(mask));
        int emailIdx = result.columns().stream().map(c -> c.name()).toList().indexOf("email");
        assertThat(result.rows()).allSatisfy(row ->
                assertThat(row.get(emailIdx).toString()).contains("***"));
        assertThat(result.appliedMaskingPolicyIds()).containsExactly(mask.policyId());
    }

    @Test
    void connectionProbeSucceeds() {
        var result = engine.testConnection(descriptor);
        assertThat(result.ok()).isTrue();
    }

    @Test
    void connectionProbeFailsForUnreachableHost() {
        assertThatThrownBy(() -> engine.testConnection(unreachableDescriptor()))
                .isInstanceOf(DatasourceConnectionTestException.class);
    }

    @Test
    void schemaIntrospectionListsScopesCollectionsAndFields() {
        var schema = engine.introspectSchema(descriptor);
        var defaultScope = schema.schemas().stream()
                .filter(s -> s.name().equals("_default")).findFirst().orElseThrow();
        var collection = defaultScope.tables().stream()
                .filter(t -> t.name().equals("_default")).findFirst().orElseThrow();
        assertThat(collection.columns()).extracting("name")
                .contains("meta().id", "name", "team", "email");
        assertThat(collection.columns().get(0).primaryKey()).isTrue();
    }

    @Test
    void schemaIntrospectionFailsForUnreachableHost() {
        assertThatThrownBy(() -> engine.introspectSchema(unreachableDescriptor()))
                .isInstanceOf(DatasourceConnectionTestException.class);
    }

    @Test
    void evictDatasourceDisconnectsAndQueriesReopenTransparently() {
        run("SELECT 1");
        engine.evictDatasource(descriptor.id()); // disconnect branch
        engine.evictDatasource(descriptor.id()); // no-op branch (already gone)
        assertThat(((SelectExecutionResult) run("SELECT * FROM _default")).rowCount())
                .isEqualTo(3);
    }

    private static DatasourceConnectionDescriptor unreachableDescriptor() {
        return new DatasourceConnectionDescriptor(UUID.randomUUID(), UUID.randomUUID(),
                DbType.COUCHBASE, "127.0.0.1", 1, "missing", "user", "", SslMode.DISABLE, 10,
                1000, true, null, false, null, "couchbase", null, null, null, null, true);
    }

    @Test
    void dryRunSelectReturnsPlan() {
        var result = dryRun("SELECT name FROM _default WHERE team = 'eng'", List.of());
        assertThat(result.supported()).isTrue();
        assertThat(result.queryType()).isEqualTo(QueryType.SELECT);
        assertThat(result.plan()).isNotNull();
        assertThat(result.plan().operation()).isNotBlank();
    }

    @Test
    void dryRunUpdatePlansWithoutMutating() {
        var result = dryRun("UPDATE _default SET bonus = 1 WHERE team = 'eng'", List.of());
        assertThat(result.supported()).isTrue();
        assertThat(result.queryType()).isEqualTo(QueryType.UPDATE);
        var after = (SelectExecutionResult) run("SELECT bonus FROM _default WHERE bonus = 1");
        assertThat(after.rowCount()).isZero();
    }

    @Test
    void dryRunAppliesRowSecurity() {
        var directive = new RowSecurityDirective(UUID.randomUUID(), "_default", "team",
                RowSecurityOperator.EQUALS, List.of("sales"));
        var result = dryRun("SELECT name FROM _default", List.of(directive));
        assertThat(result.supported()).isTrue();
        assertThat(result.appliedRowSecurityPolicyIds()).containsExactly(directive.policyId());
    }

    @Test
    void countAffectedRowsCountsDeleteMatchesWithoutMutating() {
        var result = countAffected("DELETE FROM _default WHERE team = 'eng'", List.of());
        assertThat(result.supported()).isTrue();
        assertThat(result.engineId()).isEqualTo("couchbase");
        assertThat(result.affectedRows()).isEqualTo(2);
        assertThat(result.duration()).isNotNull();
        // The count is a read-only probe — all three documents survive.
        assertThat(((SelectExecutionResult) run("SELECT * FROM _default")).rowCount()).isEqualTo(3);
    }

    @Test
    void countAffectedRowsCountsUpdateWithoutWhereAsWholeCollection() {
        var result = countAffected("UPDATE _default SET bonus = 1", List.of());
        assertThat(result.supported()).isTrue();
        assertThat(result.affectedRows()).isEqualTo(3);
    }

    @Test
    void countAffectedRowsAppliesRowSecurity() {
        var directive = new RowSecurityDirective(UUID.randomUUID(), "_default", "team",
                RowSecurityOperator.EQUALS, List.of("eng"));
        // Cy (sales, 80) is shielded by the eng-only predicate; only Bo (eng, 90) matches.
        var result = countAffected("DELETE FROM _default WHERE salary < 95", List.of(directive));
        assertThat(result.supported()).isTrue();
        assertThat(result.affectedRows()).isEqualTo(1);
    }

    @Test
    void countAffectedRowsFailsClosedOnUnsupportedShapes() {
        assertThat(countAffected("DELETE FROM _default USE KEYS ['p1']", List.of()).supported())
                .isFalse();
        assertThat(countAffected("SELECT * FROM _default", List.of()).supported()).isFalse();
    }

    private static QueryAffectedRowsResult countAffected(String query,
                                                         List<RowSecurityDirective> rls) {
        var request = new QueryExecutionRequest(descriptor.id(), query,
                engine.parse(query).type(), null, null, List.of(), List.of(), rls, false,
                List.of(query));
        return engine.countAffectedRows(new QueryEngineDryRunRequest(request, descriptor,
                Duration.ofSeconds(30)));
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

    private static SelectExecutionResult sample(String table, int maxRows,
                                                List<RowSecurityDirective> rls,
                                                List<ColumnMaskDirective> masks) {
        var request = new SampleTableRequest(descriptor.id(), descriptor.databaseName(), table,
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
