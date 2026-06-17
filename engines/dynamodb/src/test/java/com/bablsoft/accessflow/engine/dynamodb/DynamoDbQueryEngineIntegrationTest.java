package com.bablsoft.accessflow.engine.dynamodb;

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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Drives the full engine through the {@link DynamoDbQueryEngine} SPI facade — the exact surface the
 * host calls after ServiceLoader discovery — against a real {@code amazon/dynamodb-local} container,
 * proving the AF-422 acceptance behaviour: PartiQL CRUD + JSON table DDL classification and
 * execution, transaction rejection, WHERE-spliced row-level security (incl. deny-all and
 * INSERT-into-policied rejection), attribute masking (incl. nested dot-path), truncation at the row
 * cap, schema introspection, connection probing, and eviction. The fixture table {@code Users} has
 * partition key {@code id} and per-item attributes {@code tenant}, {@code name}, {@code email}, and
 * a nested {@code profile} map.
 */
class DynamoDbQueryEngineIntegrationTest {

    private static final String TABLE = "Users";
    private static final String REGION = "us-east-1";

    static final GenericContainer<?> DYNAMODB = new GenericContainer<>(
            DockerImageName.parse("amazon/dynamodb-local:2.5.2")).withExposedPorts(8000);

    private static DynamoDbQueryEngine engine;
    private static DatasourceConnectionDescriptor descriptor;
    private static DynamoDbClient adminClient;

    @BeforeAll
    static void startContainerAndEngine() {
        DYNAMODB.start();
        var endpoint = "http://" + DYNAMODB.getHost() + ":" + DYNAMODB.getMappedPort(8000);
        adminClient = DynamoDbClient.builder()
                .region(Region.of(REGION))
                .endpointOverride(URI.create(endpoint))
                .httpClient(UrlConnectionHttpClient.create())
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("dummy", "dummy-secret")))
                .build();
        adminClient.createTable(CreateTableRequest.builder().tableName(TABLE)
                .attributeDefinitions(AttributeDefinition.builder().attributeName("id")
                        .attributeType(ScalarAttributeType.S).build())
                .keySchema(KeySchemaElement.builder().attributeName("id").keyType(KeyType.HASH).build())
                .billingMode(BillingMode.PAY_PER_REQUEST).build());
        seed("u1", "acme", "Ada", "ada@x.io", "111-11-1111", "555-0100");
        seed("u2", "acme", "Bo", "bo@x.io", "222-22-2222", "555-0200");
        seed("u3", "globex", "Cy", "cy@x.io", "333-33-3333", "555-0300");

        engine = new DynamoDbQueryEngine();
        engine.initialize(new QueryEngineContext(
                TestMessages.keyEcho(), ciphertext -> ciphertext, Map.of(), Clock.systemUTC()));
        descriptor = descriptor(endpoint);
    }

    @AfterAll
    static void stopEngineAndContainer() {
        if (engine != null) {
            engine.shutdown();
        }
        if (adminClient != null) {
            adminClient.close();
        }
        DYNAMODB.stop();
    }

    @Test
    void engineIdMatchesConnectorId() {
        assertThat(engine.engineId()).isEqualTo("dynamodb");
    }

    @Test
    void parsesPartiqlAndJsonDdlClassesAndRejectsTransactions() {
        assertThat(engine.parse("SELECT * FROM \"Users\"").type()).isEqualTo(QueryType.SELECT);
        assertThat(engine.parse("{\"CreateTable\": {\"TableName\": \"X\"}}").type())
                .isEqualTo(QueryType.DDL);
        assertThatThrownBy(() -> engine.parse("EXECUTE TRANSACTION"))
                .isInstanceOf(InvalidSqlException.class);
    }

    @Test
    void selectReturnsColumnsAndRows() {
        var result = (SelectExecutionResult) run("SELECT * FROM \"Users\"");
        assertThat(result.rowCount()).isEqualTo(3);
        assertThat(result.columns()).extracting("name").contains("id", "tenant", "name", "email");
    }

    @Test
    void insertUpdateDeleteReportAffectedRows() {
        assertThat(((UpdateExecutionResult) run(
                "INSERT INTO \"Users\" VALUE {'id': 'u9', 'tenant': 'acme', 'name': 'Di'}"))
                .rowsAffected()).isEqualTo(1);
        assertThat(((UpdateExecutionResult) run(
                "UPDATE \"Users\" SET name = 'Di2' WHERE \"id\" = 'u9'")).rowsAffected()).isEqualTo(1);
        assertThat(((UpdateExecutionResult) run(
                "DELETE FROM \"Users\" WHERE \"id\" = 'u9'")).rowsAffected()).isEqualTo(1);
    }

    @Test
    void selectHonoursMaxRowsAndDetectsTruncation() {
        var result = (SelectExecutionResult) execute("SELECT * FROM \"Users\"", QueryType.SELECT, 2,
                List.of(), List.of());
        assertThat(result.rows()).hasSize(2);
        assertThat(result.truncated()).isTrue();
    }

    @Test
    void rowSecurityFiltersRows() {
        var directive = new RowSecurityDirective(UUID.randomUUID(), "Users", "tenant",
                RowSecurityOperator.EQUALS, List.of("globex"));
        var result = (SelectExecutionResult) execute("SELECT * FROM \"Users\"", QueryType.SELECT, 100,
                List.of(directive), List.of());
        assertThat(result.rowCount()).isEqualTo(1);
        assertThat(result.appliedRowSecurityPolicyIds()).containsExactly(directive.policyId());
    }

    @Test
    void rowSecurityDenyAllReturnsNothingWithoutExecuting() {
        var directive = new RowSecurityDirective(UUID.randomUUID(), "Users", "tenant",
                RowSecurityOperator.EQUALS, List.of());
        var result = (SelectExecutionResult) execute("SELECT * FROM \"Users\"", QueryType.SELECT, 100,
                List.of(directive), List.of());
        assertThat(result.rowCount()).isZero();
        assertThat(result.appliedRowSecurityPolicyIds()).containsExactly(directive.policyId());
    }

    @Test
    void insertIntoPoliciedTableIsRejected() {
        var directive = new RowSecurityDirective(UUID.randomUUID(), "Users", "tenant",
                RowSecurityOperator.EQUALS, List.of("acme"));
        assertThatThrownBy(() -> execute(
                "INSERT INTO \"Users\" VALUE {'id': 'u99', 'tenant': 'acme'}", QueryType.INSERT, 100,
                List.of(directive), List.of()))
                .isInstanceOf(UnrewritableRowSecurityException.class);
    }

    @Test
    void columnMaskRedactsTopLevelAndNestedAttributes() {
        var emailMask = new ColumnMaskDirective("email", MaskingStrategy.EMAIL, Map.of(),
                UUID.randomUUID());
        var ssnMask = new ColumnMaskDirective("profile.ssn", MaskingStrategy.FULL, Map.of(),
                UUID.randomUUID());
        var result = (SelectExecutionResult) execute("SELECT * FROM \"Users\"", QueryType.SELECT, 100,
                List.of(), List.of(emailMask, ssnMask));
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
    void sampleTableReturnsTableRows() {
        var result = sample("Users", 100, List.of(), List.of());
        assertThat(result.rowCount()).isEqualTo(3);
        assertThat(result.columns()).extracting("name").contains("id", "tenant", "name", "email");
    }

    @Test
    void sampleTableHonoursRowCap() {
        var result = sample("Users", 2, List.of(), List.of());
        assertThat(result.rows()).hasSize(2);
        assertThat(result.truncated()).isTrue();
    }

    @Test
    void sampleTableAppliesRowSecurity() {
        var directive = new RowSecurityDirective(UUID.randomUUID(), "Users", "tenant",
                RowSecurityOperator.EQUALS, List.of("globex"));
        var result = sample("Users", 100, List.of(directive), List.of());
        assertThat(result.rowCount()).isEqualTo(1);
        assertThat(result.appliedRowSecurityPolicyIds()).containsExactly(directive.policyId());
    }

    @Test
    void sampleTableAppliesRecursiveColumnMask() {
        var emailMask = new ColumnMaskDirective("email", MaskingStrategy.EMAIL, Map.of(),
                UUID.randomUUID());
        var ssnMask = new ColumnMaskDirective("profile.ssn", MaskingStrategy.FULL, Map.of(),
                UUID.randomUUID());
        var result = sample("Users", 100, List.of(), List.of(emailMask, ssnMask));
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
    void createAndDeleteTableViaJsonCommand() {
        var create = """
                {"CreateTable": {"TableName": "Widgets",
                    "AttributeDefinitions": [{"AttributeName": "id", "AttributeType": "S"}],
                    "KeySchema": [{"AttributeName": "id", "KeyType": "HASH"}],
                    "BillingMode": "PAY_PER_REQUEST"}}""";
        assertThat(((UpdateExecutionResult) run(create)).rowsAffected()).isZero();
        assertThat(adminClient.listTables().tableNames()).contains("Widgets");
        assertThat(((UpdateExecutionResult) run("{\"DeleteTable\": {\"TableName\": \"Widgets\"}}"))
                .rowsAffected()).isZero();
        assertThat(adminClient.listTables().tableNames()).doesNotContain("Widgets");
    }

    @Test
    void connectionProbeSucceedsAndFailsForUnreachableEndpoint() {
        assertThat(engine.testConnection(descriptor).ok()).isTrue();
        assertThatThrownBy(() -> engine.testConnection(descriptor("http://127.0.0.1:1")))
                .isInstanceOf(DatasourceConnectionTestException.class);
    }

    @Test
    void schemaIntrospectionListsTablesAndFlagsPartitionKey() {
        var schema = engine.introspectSchema(descriptor);
        var region = schema.schemas().stream()
                .filter(s -> s.name().equals(REGION)).findFirst().orElseThrow();
        var table = region.tables().stream()
                .filter(t -> t.name().equals(TABLE)).findFirst().orElseThrow();
        assertThat(table.columns()).extracting("name").contains("id", "tenant", "name", "email");
        assertThat(table.columns()).filteredOn("primaryKey", true).extracting("name")
                .containsExactly("id");
    }

    @Test
    void evictDatasourceClosesClientAndQueriesReopenTransparently() {
        run("SELECT * FROM \"Users\"");
        engine.evictDatasource(descriptor.id()); // close branch
        engine.evictDatasource(descriptor.id()); // no-op branch (already gone)
        assertThat(((SelectExecutionResult) run("SELECT * FROM \"Users\"")).rowCount()).isEqualTo(3);
    }

    // ---- helpers ------------------------------------------------------------------------------

    private static void seed(String id, String tenant, String name, String email, String ssn,
                             String phone) {
        adminClient.putItem(PutItemRequest.builder().tableName(TABLE).item(Map.of(
                "id", AttributeValue.fromS(id),
                "tenant", AttributeValue.fromS(tenant),
                "name", AttributeValue.fromS(name),
                "email", AttributeValue.fromS(email),
                "profile", AttributeValue.fromM(Map.of(
                        "ssn", AttributeValue.fromS(ssn),
                        "phone", AttributeValue.fromS(phone))))).build());
    }

    private static int columnIndex(SelectExecutionResult result, String name) {
        var names = result.columns().stream().map(c -> c.name()).toList();
        return names.indexOf(name);
    }

    private static DatasourceConnectionDescriptor descriptor(String endpoint) {
        return new DatasourceConnectionDescriptor(UUID.randomUUID(), UUID.randomUUID(),
                DbType.DYNAMODB, null, null, REGION, "dummy", "dummy-secret", SslMode.DISABLE, 10,
                1000, true, null, false, null, "dynamodb", endpoint, null, null, null, true, null);
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
