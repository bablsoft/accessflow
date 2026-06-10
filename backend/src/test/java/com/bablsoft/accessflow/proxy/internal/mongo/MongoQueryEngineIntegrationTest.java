package com.bablsoft.accessflow.proxy.internal.mongo;

import com.bablsoft.accessflow.core.api.ConnectionTestResult;
import com.bablsoft.accessflow.core.api.CredentialEncryptionService;
import com.bablsoft.accessflow.core.api.DatasourceConnectionDescriptor;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.MaskingStrategy;
import com.bablsoft.accessflow.core.api.RowSecurityOperator;
import com.bablsoft.accessflow.core.api.SslMode;
import com.bablsoft.accessflow.core.internal.DefaultMongoConnectionStringFactory;
import com.bablsoft.accessflow.core.internal.mongo.MongoConnectionProbe;
import com.bablsoft.accessflow.core.internal.mongo.MongoSchemaIntrospector;
import com.bablsoft.accessflow.proxy.api.ColumnMaskDirective;
import com.bablsoft.accessflow.proxy.api.QueryExecutionRequest;
import com.bablsoft.accessflow.proxy.api.RowSecurityDirective;
import com.bablsoft.accessflow.proxy.api.SelectExecutionResult;
import com.bablsoft.accessflow.proxy.api.UpdateExecutionResult;
import com.bablsoft.accessflow.core.api.QueryType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;
import org.testcontainers.mongodb.MongoDBContainer;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

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

    private final CredentialEncryptionService encryption = new CredentialEncryptionService() {
        @Override
        public String encrypt(String plaintext) {
            return plaintext;
        }

        @Override
        public String decrypt(String ciphertext) {
            return ciphertext;
        }
    };
    private final DefaultMongoConnectionStringFactory factory = new DefaultMongoConnectionStringFactory();
    private final StaticMessageSource messages = messages();
    private final MongoQueryParser parser = new MongoQueryParser(messages);
    private MongoClientManager clientManager;
    private MongoQueryExecutor executor;
    private DatasourceConnectionDescriptor descriptor;

    private static StaticMessageSource messages() {
        var source = new StaticMessageSource();
        source.setUseCodeAsDefaultMessage(true);
        return source;
    }

    @BeforeEach
    void setUp() {
        var props = new ProxyMongoProperties(null, null, null);
        clientManager = new MongoClientManager(encryption, factory, props);
        executor = new MongoQueryExecutor(clientManager, parser,
                new MongoRowSecurityApplier(messages), new MongoResultMapper(),
                new MongoExceptionTranslator(messages), Clock.systemUTC());
        descriptor = descriptor("itest_" + UUID.randomUUID().toString().replace("-", ""));
        // Seed a couple of documents through the engine itself.
        run("db.people.insertMany(["
                + "{ name: 'Ada', team: 'eng', salary: 100, email: 'ada@x.io' },"
                + "{ name: 'Bo', team: 'eng', salary: 90, email: 'bo@x.io' },"
                + "{ name: 'Cy', team: 'sales', salary: 80, email: 'cy@x.io' } ])");
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
    void connectionProbePings() {
        var probe = new MongoConnectionProbe(encryption, factory);
        ConnectionTestResult result = probe.test(descriptor);
        assertThat(result.ok()).isTrue();
    }

    @Test
    void schemaIntrospectionListsCollectionsAndFields() {
        var introspector = new MongoSchemaIntrospector(encryption, factory);
        var schema = introspector.introspect(descriptor);
        var collection = schema.schemas().get(0).tables().stream()
                .filter(t -> t.name().equals("people")).findFirst().orElseThrow();
        assertThat(collection.columns()).extracting("name").contains("_id", "name", "team");
        assertThat(collection.columns().stream().filter(c -> c.name().equals("_id"))
                .findFirst().orElseThrow().primaryKey()).isTrue();
    }

    private Object run(String query) {
        return execute(query, parser.parse(query).type(), 1000, List.of(), List.of());
    }

    private Object execute(String query, QueryType type, int maxRows,
                           List<RowSecurityDirective> rls, List<ColumnMaskDirective> masks) {
        var request = new QueryExecutionRequest(descriptor.id(), query, type, null, null,
                List.of(), masks, rls, false, List.of(query));
        return executor.execute(request, descriptor, maxRows, Duration.ofSeconds(10));
    }

    private DatasourceConnectionDescriptor descriptor(String database) {
        return new DatasourceConnectionDescriptor(UUID.randomUUID(), UUID.randomUUID(),
                DbType.MONGODB, MONGO.getHost(), MONGO.getFirstMappedPort(), database, null, "",
                SslMode.DISABLE, 10, 1000, true, null, false, null, null, null, null, null, null,
                true);
    }
}
