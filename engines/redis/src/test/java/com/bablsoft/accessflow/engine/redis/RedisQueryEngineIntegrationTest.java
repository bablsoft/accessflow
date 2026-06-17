package com.bablsoft.accessflow.engine.redis;

import com.bablsoft.accessflow.core.api.ColumnMaskDirective;
import com.bablsoft.accessflow.core.api.DatasourceConnectionDescriptor;
import com.bablsoft.accessflow.core.api.DatasourceConnectionTestException;
import com.bablsoft.accessflow.core.api.DbType;
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
import org.junit.jupiter.api.BeforeEach;
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
 * Drives the full engine through the {@link RedisQueryEngine} SPI facade — the exact surface the
 * host calls after ServiceLoader discovery — against a real Redis container, proving command
 * classification, result shaping, field masking, row-security fail-closed, introspection,
 * connection probing, and eviction.
 */
class RedisQueryEngineIntegrationTest {

    @SuppressWarnings("resource")
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:8")).withExposedPorts(6379);

    @BeforeAll
    static void startContainer() {
        REDIS.start();
    }

    @AfterAll
    static void stopContainer() {
        REDIS.stop();
    }

    private RedisQueryEngine engine;
    private DatasourceConnectionDescriptor descriptor;

    @BeforeEach
    void setUp() {
        engine = new RedisQueryEngine();
        engine.initialize(new QueryEngineContext(
                TestMessages.keyEcho(), ciphertext -> ciphertext, Map.of(), Clock.systemUTC()));
        descriptor = descriptor();
        run("FLUSHDB");
    }

    @Test
    void engineIdMatchesConnectorId() {
        assertThat(engine.engineId()).isEqualTo("redis");
    }

    @Test
    void setReportsStatusAndGetReturnsValue() {
        assertThat(((UpdateExecutionResult) run("SET user:1 Ada")).rowsAffected()).isEqualTo(1);
        var get = (SelectExecutionResult) run("GET user:1");
        assertThat(get.columns()).extracting("name").containsExactly("value");
        assertThat(get.rows().get(0).get(0)).isEqualTo("Ada");
    }

    @Test
    void mgetReturnsKeyValueRows() {
        run("MSET user:1 Ada user:2 Bo");
        var result = (SelectExecutionResult) run("MGET user:1 user:2");
        assertThat(result.rowCount()).isEqualTo(2);
        assertThat(result.columns()).extracting("name").containsExactly("key", "value");
    }

    @Test
    void hashFieldsBecomeColumns() {
        run("HSET session:1 name Ada email ada@x.io");
        var result = (SelectExecutionResult) run("HGETALL session:1");
        assertThat(result.columns()).extracting("name").contains("name", "email");
        assertThat(result.rowCount()).isEqualTo(1);
    }

    @Test
    void incrIsValueReturningSelectDespiteUpdateClassification() {
        assertThat(engine.parse("INCR counter:1").type()).isEqualTo(QueryType.UPDATE);
        var result = (SelectExecutionResult) run("INCR counter:1");
        assertThat(result.rows().get(0).get(0)).isEqualTo(1L);
    }

    @Test
    void listWriteThenRangeAndValueReturningPop() {
        run("RPUSH list:1 a b c");
        var range = (SelectExecutionResult) run("LRANGE list:1 0 -1");
        assertThat(range.rowCount()).isEqualTo(3);
        var pop = (SelectExecutionResult) run("LPOP list:1");
        assertThat(pop.rows().get(0).get(0)).isEqualTo("a");
    }

    @Test
    void delAndGetdelReportExpectedShapes() {
        run("SET user:1 Ada");
        assertThat(((UpdateExecutionResult) run("DEL user:1")).rowsAffected()).isEqualTo(1);
        run("SET user:2 Bo");
        var getdel = (SelectExecutionResult) run("GETDEL user:2");
        assertThat(getdel.rows().get(0).get(0)).isEqualTo("Bo");
        assertThat(((SelectExecutionResult) run("EXISTS user:2")).rows().get(0).get(0)).isEqualTo(0L);
    }

    @Test
    void scanAndKeysReturnKeyColumn() {
        run("MSET orders:1 a orders:2 b other:1 c");
        var scan = (SelectExecutionResult) run("SCAN 0 MATCH orders:* COUNT 100");
        assertThat(scan.columns()).extracting("name").containsExactly("key");
        assertThat(scan.rows()).isNotEmpty();
        var keys = (SelectExecutionResult) run("KEYS orders:*");
        assertThat(keys.rowCount()).isEqualTo(2);
    }

    @Test
    void readTruncationIsDetected() {
        run("RPUSH list:1 a b c d e");
        var result = (SelectExecutionResult) execute("LRANGE list:1 0 -1", QueryType.SELECT, 2,
                List.of(), List.of());
        assertThat(result.rows()).hasSize(2);
        assertThat(result.truncated()).isTrue();
    }

    @Test
    void forbiddenCommandRejectedAtParse() {
        assertThatThrownBy(() -> engine.parse("EVAL \"return 1\" 0"))
                .hasMessageContaining("error.redis.forbidden_command");
    }

    @Test
    void rowSecurityOnReferencedPrefixFailsClosed() {
        run("SET user:1 Ada");
        var directive = new RowSecurityDirective(UUID.randomUUID(), "user", "owner",
                RowSecurityOperator.EQUALS, List.of("someone"));
        assertThatThrownBy(() -> execute("GET user:1", QueryType.SELECT, 100, List.of(directive),
                List.of()))
                .isInstanceOf(UnrewritableRowSecurityException.class)
                .hasMessageContaining("error.row_security_redis_unsupported");
    }

    @Test
    void rowSecurityOnUnreferencedPrefixDoesNotBlock() {
        run("SET user:1 Ada");
        var directive = new RowSecurityDirective(UUID.randomUUID(), "orders", "owner",
                RowSecurityOperator.EQUALS, List.of("someone"));
        var result = (SelectExecutionResult) execute("GET user:1", QueryType.SELECT, 100,
                List.of(directive), List.of());
        assertThat(result.rows().get(0).get(0)).isEqualTo("Ada");
    }

    @Test
    void maskingRedactsHashField() {
        run("HSET session:1 name Ada email ada@x.io");
        var mask = new ColumnMaskDirective("session.email", MaskingStrategy.EMAIL, Map.of(),
                UUID.randomUUID());
        var result = (SelectExecutionResult) execute("HGETALL session:1", QueryType.SELECT, 100,
                List.of(), List.of(mask));
        int emailIdx = result.columns().stream().map(c -> c.name()).toList().indexOf("email");
        assertThat(result.rows().get(0).get(emailIdx)).isEqualTo("a***@x.io");
        assertThat(result.appliedMaskingPolicyIds()).isNotEmpty();
    }

    @Test
    void sampleTableReturnsKeyValuesForStringPrefix() {
        run("MSET user:1 Ada user:2 Bo");
        var result = sample("user", 100, List.of(), List.of());
        assertThat(result.rowCount()).isEqualTo(2);
        assertThat(result.columns()).extracting(c -> c.name()).containsExactly("key", "value");
    }

    @Test
    void sampleTableHonoursRowCap() {
        run("MSET user:1 Ada user:2 Bo user:3 Cy");
        var result = sample("user", 2, List.of(), List.of());
        assertThat(result.rows()).hasSize(2);
        assertThat(result.truncated()).isTrue();
    }

    @Test
    void sampleTableFailsClosedForPoliciedPrefix() {
        run("SET user:1 Ada");
        var directive = new RowSecurityDirective(UUID.randomUUID(), "user", "owner",
                RowSecurityOperator.EQUALS, List.of("someone"));
        assertThatThrownBy(() -> sample("user", 100, List.of(directive), List.of()))
                .isInstanceOf(UnrewritableRowSecurityException.class)
                .hasMessageContaining("error.row_security_redis_unsupported");
    }

    @Test
    void sampleTableMasksHashFields() {
        run("HSET session:1 name Ada email ada@x.io");
        var mask = new ColumnMaskDirective("session.email", MaskingStrategy.EMAIL, Map.of(),
                UUID.randomUUID());
        var result = sample("session", 100, List.of(), List.of(mask));
        int emailIdx = result.columns().stream().map(c -> c.name()).toList().indexOf("email");
        assertThat(result.rows().get(0).get(emailIdx)).isEqualTo("a***@x.io");
        assertThat(result.appliedMaskingPolicyIds()).isNotEmpty();
    }

    @Test
    void connectionProbePings() {
        assertThat(engine.testConnection(descriptor).ok()).isTrue();
    }

    @Test
    void connectionProbeFailsForUnreachableHost() {
        assertThatThrownBy(() -> engine.testConnection(unreachableDescriptor()))
                .isInstanceOf(DatasourceConnectionTestException.class);
    }

    @Test
    void schemaIntrospectionGroupsKeysByPrefix() {
        run("SET user:1 Ada");
        run("HSET session:1 token abc");
        var schema = engine.introspectSchema(descriptor);
        var tables = schema.schemas().get(0).tables();
        assertThat(tables).extracting("name").contains("user", "session");
        var session = tables.stream().filter(t -> t.name().equals("session")).findFirst().orElseThrow();
        assertThat(session.columns()).extracting("name").contains("token");
    }

    @Test
    void evictAndShutdownAreIdempotentAndQueriesReopen() {
        run("SET user:1 Ada");
        engine.evictDatasource(descriptor.id());
        engine.evictDatasource(descriptor.id());
        engine.shutdown();
        assertThat(((SelectExecutionResult) run("GET user:1")).rows().get(0).get(0)).isEqualTo("Ada");
    }

    private Object run(String query) {
        return execute(query, engine.parse(query).type(), 1000, List.of(), List.of());
    }

    private SelectExecutionResult sample(String prefix, int maxRows, List<RowSecurityDirective> rls,
                                         List<ColumnMaskDirective> masks) {
        var request = new SampleTableRequest(descriptor.id(), descriptor.databaseName(), prefix,
                List.of(), masks, rls, null, null);
        return (SelectExecutionResult) engine.sampleTable(new QueryEngineSampleRequest(request,
                descriptor, maxRows, Duration.ofSeconds(10)));
    }

    private Object execute(String query, QueryType type, int maxRows, List<RowSecurityDirective> rls,
                           List<ColumnMaskDirective> masks) {
        var request = new QueryExecutionRequest(descriptor.id(), query, type, null, null,
                List.of(), masks, rls, false, List.of(query));
        return engine.execute(new QueryEngineExecutionRequest(request, descriptor, maxRows,
                Duration.ofSeconds(10)));
    }

    private DatasourceConnectionDescriptor descriptor() {
        return new DatasourceConnectionDescriptor(UUID.randomUUID(), UUID.randomUUID(),
                DbType.REDIS, REDIS.getHost(), REDIS.getMappedPort(6379), "0", null, "",
                SslMode.DISABLE, 10, 1000, true, null, false, null, null, null, null, null, null, true);
    }

    private DatasourceConnectionDescriptor unreachableDescriptor() {
        return new DatasourceConnectionDescriptor(UUID.randomUUID(), UUID.randomUUID(),
                DbType.REDIS, "127.0.0.1", 1, "0", null, "", SslMode.DISABLE, 10, 1000, true, null,
                false, null, null, null, null, null, null, true);
    }
}
