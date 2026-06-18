package com.bablsoft.accessflow.engine.mongodb;

import com.bablsoft.accessflow.core.api.ColumnMaskDirective;
import com.bablsoft.accessflow.core.api.ConnectionTestResult;
import com.bablsoft.accessflow.core.api.DatasourceConnectionDescriptor;
import com.bablsoft.accessflow.core.api.DatasourceConnectionTestException;
import com.bablsoft.accessflow.core.api.DbType;
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
import com.bablsoft.accessflow.core.api.UpdateExecutionResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.mongodb.MongoDBContainer;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Drives the full engine through the {@link MongoQueryEngine} SPI facade — the exact surface the
 * host calls after ServiceLoader discovery (AF-414) — against a real MongoDB container, proving
 * the AF-411 behaviour (CRUD/DDL in both query syntaxes, {@code $match} row-level security, field
 * masking, introspection, connection probing, eviction) is preserved in the plugin.
 */
class MongoQueryEngineIntegrationTest {

    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:8.0");

    @BeforeAll
    static void startContainer() {
        MONGO.start();
    }

    @AfterAll
    static void stopContainer() {
        MONGO.stop();
    }

    private MongoQueryEngine engine;
    private DatasourceConnectionDescriptor descriptor;

    @BeforeEach
    void setUp() {
        engine = new MongoQueryEngine();
        engine.initialize(new QueryEngineContext(
                TestMessages.keyEcho(), ciphertext -> ciphertext, Map.of(), Clock.systemUTC()));
        descriptor = descriptor("itest_" + UUID.randomUUID().toString().replace("-", ""));
        // Seed a couple of documents through the engine itself.
        run("db.people.insertMany(["
                + "{ name: 'Ada', team: 'eng', salary: 100, email: 'ada@x.io' },"
                + "{ name: 'Bo', team: 'eng', salary: 90, email: 'bo@x.io' },"
                + "{ name: 'Cy', team: 'sales', salary: 80, email: 'cy@x.io' } ])");
    }

    @Test
    void engineIdMatchesConnectorId() {
        assertThat(engine.engineId()).isEqualTo("mongodb");
    }

    @Test
    void insertManyReportsAffectedCount() {
        var result = run("db.t.insertMany([{ a: 1 }, { a: 2 }])");
        assertThat(result).isInstanceOf(UpdateExecutionResult.class);
        assertThat(((UpdateExecutionResult) result).rowsAffected()).isEqualTo(2);
    }

    @Test
    void findReturnsDocumentsAsColumnsAndRows() {
        var result = (SelectExecutionResult) run("db.people.find({ team: 'eng' })");
        assertThat(result.rowCount()).isEqualTo(2);
        assertThat(result.columns()).extracting("name").contains("name", "team", "salary");
    }

    @Test
    void findHonoursLimitAndDetectsTruncation() {
        var result = (SelectExecutionResult) execute("db.people.find({})", QueryType.SELECT, 2,
                List.of(), List.of());
        assertThat(result.rows()).hasSize(2);
        assertThat(result.truncated()).isTrue();
    }

    @Test
    void aggregateGroups() {
        var result = (SelectExecutionResult) run(
                "db.people.aggregate([{ $group: { _id: '$team', total: { $sum: '$salary' } } }])");
        assertThat(result.rowCount()).isEqualTo(2);
    }

    @Test
    void countAndDistinct() {
        var count = (SelectExecutionResult) run("db.people.countDocuments({ team: 'eng' })");
        assertThat(count.rows().get(0).get(0)).isEqualTo(2L);
        var distinct = (SelectExecutionResult) run("db.people.distinct('team', {})");
        assertThat(distinct.rowCount()).isEqualTo(2);
    }

    @Test
    void jsonCommandFormExecutesThroughTheSamePath() {
        var result = (SelectExecutionResult) run(
                "{ \"find\": \"people\", \"filter\": { \"team\": \"eng\" } }");
        assertThat(result.rowCount()).isEqualTo(2);
    }

    @Test
    void updateAndDeleteReportCounts() {
        assertThat(((UpdateExecutionResult) run(
                "db.people.updateMany({ team: 'eng' }, { $set: { bonus: 1 } })")).rowsAffected())
                .isEqualTo(2);
        assertThat(((UpdateExecutionResult) run("db.people.deleteOne({ name: 'Cy' })"))
                .rowsAffected()).isEqualTo(1);
    }

    @Test
    void ddlCreateIndexDropCollection() {
        assertThat(((UpdateExecutionResult) run("db.people.createIndex({ email: 1 })"))
                .rowsAffected()).isZero();
        assertThat(((UpdateExecutionResult) run("db.t2.drop()")).rowsAffected()).isZero();
    }

    @Test
    void ddlCreateNamedIndexThenDropIt() {
        assertThat(((UpdateExecutionResult) run("db.people.createIndex({ name: 1 }, { name: 'name_idx' })"))
                .rowsAffected()).isZero();
        assertThat(((UpdateExecutionResult) run("db.people.dropIndex('name_idx')"))
                .rowsAffected()).isZero();
    }

    @Test
    void replaceOneAndFindOneAndUpdateReportCounts() {
        assertThat(((UpdateExecutionResult) run(
                "db.people.replaceOne({ name: 'Bo' }, { name: 'Bo', team: 'eng', salary: 95 })"))
                .rowsAffected()).isEqualTo(1);
        assertThat(((UpdateExecutionResult) run(
                "db.people.findOneAndUpdate({ name: 'Ada' }, { $set: { salary: 110 } })"))
                .rowsAffected()).isEqualTo(1);
        assertThat(((UpdateExecutionResult) run(
                "db.people.findOneAndUpdate({ name: 'Nobody' }, { $set: { x: 1 } })"))
                .rowsAffected()).isZero();
    }

    @Test
    void findHonoursSortSkipAndProjection() {
        var result = (SelectExecutionResult) run(
                "db.people.find({}, { name: 1, _id: 0 }).sort({ salary: -1 }).skip(1)");
        assertThat(result.columns()).extracting("name").containsExactly("name");
        assertThat(result.rowCount()).isEqualTo(2);
    }

    @Test
    void evictDatasourceClosesCachedClient() {
        run("db.people.find({})"); // opens + caches a client
        engine.evictDatasource(descriptor.id()); // close branch
        engine.evictDatasource(descriptor.id()); // no-op branch (already gone)
        // A subsequent query transparently reopens the client.
        assertThat(((SelectExecutionResult) run("db.people.find({})")).rowCount()).isEqualTo(3);
    }

    @Test
    void shutdownClosesAllClientsAndQueriesReopen() {
        run("db.people.find({})");
        engine.shutdown();
        assertThat(((SelectExecutionResult) run("db.people.find({})")).rowCount()).isEqualTo(3);
    }

    @Test
    void connectionProbeFailsForUnreachableHost() {
        assertThatThrownBy(() -> engine.testConnection(unreachableDescriptor()))
                .isInstanceOf(DatasourceConnectionTestException.class);
    }

    @Test
    void schemaIntrospectionFailsForUnreachableHost() {
        assertThatThrownBy(() -> engine.introspectSchema(unreachableDescriptor()))
                .isInstanceOf(DatasourceConnectionTestException.class);
    }

    private DatasourceConnectionDescriptor unreachableDescriptor() {
        // Override URI with a tiny server-selection timeout so the failure is fast.
        return new DatasourceConnectionDescriptor(UUID.randomUUID(), UUID.randomUUID(),
                DbType.MONGODB, null, null, "db", null, "", SslMode.DISABLE, 10, 1000, true, null,
                false, null, null, "mongodb://127.0.0.1:1/db?serverSelectionTimeoutMS=300", null,
                null, null, true);
    }

    @Test
    void rowSecurityFiltersDocuments() {
        var directive = new RowSecurityDirective(UUID.randomUUID(), "people", "team",
                RowSecurityOperator.EQUALS, List.of("sales"));
        var result = (SelectExecutionResult) execute("db.people.find({})", QueryType.SELECT, 100,
                List.of(directive), List.of());
        assertThat(result.rowCount()).isEqualTo(1);
        assertThat(result.appliedRowSecurityPolicyIds()).containsExactly(directive.policyId());
    }

    @Test
    void columnMaskRedactsField() {
        var mask = new ColumnMaskDirective("people.email", MaskingStrategy.EMAIL, Map.of(),
                UUID.randomUUID());
        var result = (SelectExecutionResult) execute("db.people.find({ name: 'Ada' })",
                QueryType.SELECT, 100, List.of(), List.of(mask));
        int emailIdx = result.columns().stream().map(c -> c.name()).toList().indexOf("email");
        assertThat(result.rows().get(0).get(emailIdx)).isEqualTo("a***@x.io");
    }

    @Test
    void sampleTableReturnsCollectionDocuments() {
        var result = sample("people", 100, List.of(), List.of());
        assertThat(result.rowCount()).isEqualTo(3);
        assertThat(result.columns()).extracting("name").contains("name", "team", "email");
    }

    @Test
    void sampleTableHonoursRowCap() {
        var result = sample("people", 2, List.of(), List.of());
        assertThat(result.rows()).hasSize(2);
        assertThat(result.truncated()).isTrue();
    }

    @Test
    void sampleTableAppliesRowSecurity() {
        var directive = new RowSecurityDirective(UUID.randomUUID(), "people", "team",
                RowSecurityOperator.EQUALS, List.of("sales"));
        var result = sample("people", 100, List.of(directive), List.of());
        assertThat(result.rowCount()).isEqualTo(1);
        assertThat(result.appliedRowSecurityPolicyIds()).containsExactly(directive.policyId());
    }

    @Test
    void sampleTableAppliesColumnMask() {
        var mask = new ColumnMaskDirective("people.email", MaskingStrategy.EMAIL, Map.of(),
                UUID.randomUUID());
        var result = sample("people", 100, List.of(), List.of(mask));
        int emailIdx = result.columns().stream().map(c -> c.name()).toList().indexOf("email");
        assertThat(result.rows()).allSatisfy(row ->
                assertThat(row.get(emailIdx).toString()).contains("***"));
    }

    @Test
    void connectionProbePings() {
        ConnectionTestResult result = engine.testConnection(descriptor);
        assertThat(result.ok()).isTrue();
    }

    @Test
    void schemaIntrospectionListsCollectionsAndFields() {
        var schema = engine.introspectSchema(descriptor);
        var collection = schema.schemas().get(0).tables().stream()
                .filter(t -> t.name().equals("people")).findFirst().orElseThrow();
        assertThat(collection.columns()).extracting("name").contains("_id", "name", "team");
        assertThat(collection.columns().stream().filter(c -> c.name().equals("_id"))
                .findFirst().orElseThrow().primaryKey()).isTrue();
    }

    @Test
    void dryRunFindReturnsQueryPlannerPlan() {
        var result = dryRun("db.people.find({ team: 'eng' })", List.of());
        assertThat(result.supported()).isTrue();
        assertThat(result.queryType()).isEqualTo(QueryType.SELECT);
        assertThat(result.plan()).isNotNull();
        assertThat(result.rawPlan()).isNotNull();
    }

    @Test
    void dryRunUpdatePlansWithoutMutating() {
        var result = dryRun("db.people.updateMany({ team: 'eng' }, { $set: { bonus: 5 } })", List.of());
        assertThat(result.supported()).isTrue();
        assertThat(result.queryType()).isEqualTo(QueryType.UPDATE);
        // No mutation occurred: no document gained the bonus field.
        var withBonus = (SelectExecutionResult) run("db.people.find({ bonus: 5 })");
        assertThat(withBonus.rowCount()).isZero();
    }

    @Test
    void dryRunInsertIsUnsupportedAndDoesNotInsert() {
        var result = dryRun("db.people.insertOne({ name: 'Zed' })", List.of());
        assertThat(result.supported()).isFalse();
        assertThat(((SelectExecutionResult) run("db.people.find({ name: 'Zed' })")).rowCount())
                .isZero();
    }

    @Test
    void dryRunAppliesRowSecurity() {
        var directive = new RowSecurityDirective(UUID.randomUUID(), "people", "team",
                RowSecurityOperator.EQUALS, List.of("sales"));
        var result = dryRun("db.people.find({})", List.of(directive));
        assertThat(result.supported()).isTrue();
        assertThat(result.appliedRowSecurityPolicyIds()).containsExactly(directive.policyId());
    }

    private QueryDryRunResult dryRun(String query, List<RowSecurityDirective> rls) {
        var type = engine.parse(query).type();
        var request = new QueryExecutionRequest(descriptor.id(), query, type, null, null,
                List.of(), List.of(), rls, false, List.of(query));
        return engine.dryRun(new QueryEngineDryRunRequest(request, descriptor,
                Duration.ofSeconds(10)));
    }

    private Object run(String query) {
        return execute(query, engine.parse(query).type(), 1000, List.of(), List.of());
    }

    private SelectExecutionResult sample(String table, int maxRows,
                                         List<RowSecurityDirective> rls,
                                         List<ColumnMaskDirective> masks) {
        var request = new SampleTableRequest(descriptor.id(), descriptor.databaseName(), table,
                List.of(), masks, rls, null, null);
        return (SelectExecutionResult) engine.sampleTable(new QueryEngineSampleRequest(request,
                descriptor, maxRows, Duration.ofSeconds(10)));
    }

    private Object execute(String query, QueryType type, int maxRows,
                           List<RowSecurityDirective> rls, List<ColumnMaskDirective> masks) {
        var request = new QueryExecutionRequest(descriptor.id(), query, type, null, null,
                List.of(), masks, rls, false, List.of(query));
        return engine.execute(new QueryEngineExecutionRequest(request, descriptor, maxRows,
                Duration.ofSeconds(10)));
    }

    private DatasourceConnectionDescriptor descriptor(String database) {
        return new DatasourceConnectionDescriptor(UUID.randomUUID(), UUID.randomUUID(),
                DbType.MONGODB, MONGO.getHost(), MONGO.getFirstMappedPort(), database, null, "",
                SslMode.DISABLE, 10, 1000, true, null, false, null, null, null, null, null, null,
                true);
    }
}
