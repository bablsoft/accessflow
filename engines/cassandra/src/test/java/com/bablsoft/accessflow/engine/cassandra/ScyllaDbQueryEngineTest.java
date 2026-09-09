package com.bablsoft.accessflow.engine.cassandra;

import com.bablsoft.accessflow.core.api.QueryEngineContext;
import com.bablsoft.accessflow.core.api.QueryEngineRowSecurityRequest;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.RowSecurityDirective;
import com.bablsoft.accessflow.core.api.RowSecurityOperator;
import com.bablsoft.accessflow.core.api.RowSecurityOutcome;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ScyllaDbQueryEngineTest {

    private static QueryEngineContext context() {
        return new QueryEngineContext(TestMessages.keyEcho(), ciphertext -> ciphertext, Map.of(),
                Clock.systemUTC());
    }

    @Test
    void cassandraAndScyllaExposeDistinctEngineIds() {
        assertThat(new CassandraQueryEngine().engineId()).isEqualTo("cassandra");
        assertThat(new ScyllaDbQueryEngine().engineId()).isEqualTo("scylladb");
    }

    @Test
    void scyllaIsACassandraEngineAndReusesItsParser() {
        var engine = new ScyllaDbQueryEngine();
        assertThat(engine).isInstanceOf(CassandraQueryEngine.class);
        engine.initialize(context());
        var result = engine.parse("SELECT * FROM users WHERE id = 1");
        assertThat(result.type()).isEqualTo(QueryType.SELECT);
        assertThat(result.referencedTables()).containsExactly("users");
    }

    @Test
    void usingTheEngineBeforeInitializeFails() {
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> new CassandraQueryEngine().parse("SELECT * FROM users"))
                .isInstanceOf(IllegalStateException.class);
    }

    // ---- offline row-security classification (AF-630) -------------------------------------------

    private static QueryEngineRowSecurityRequest request(String query,
                                                         RowSecurityDirective... directives) {
        return new QueryEngineRowSecurityRequest(UUID.randomUUID(), query, List.of(directives));
    }

    private static RowSecurityDirective tenantEquals(String table) {
        return new RowSecurityDirective(UUID.randomUUID(), table, "tenant_id",
                RowSecurityOperator.EQUALS, List.of(7));
    }

    private static CassandraQueryEngine initializedCassandra() {
        var engine = new CassandraQueryEngine();
        engine.initialize(context());
        return engine;
    }

    @Test
    void classifyRowSecurityShortCircuitsWhenThereAreNoDirectives() {
        var result = initializedCassandra()
                .classifyRowSecurity(request("SELECT * FROM users WHERE id = 1"));
        assertThat(result.outcome()).isEqualTo(RowSecurityOutcome.NOT_APPLICABLE);
        assertThat(result.engineId()).isEqualTo("cassandra");
    }

    @Test
    void classifyRowSecurityReportsUnknownWhenTheKeyColumnsDecideIt() {
        var result = initializedCassandra().classifyRowSecurity(
                request("SELECT * FROM users WHERE id = 1", tenantEquals("users")));
        assertThat(result.outcome()).isEqualTo(RowSecurityOutcome.UNKNOWN);
        assertThat(result.reason()).isNotBlank();
    }

    @Test
    void classifyRowSecurityReportsFailClosedForAPoliciedInsert() {
        var result = initializedCassandra().classifyRowSecurity(
                request("INSERT INTO users (id) VALUES (1)", tenantEquals("users")));
        assertThat(result.outcome()).isEqualTo(RowSecurityOutcome.FAIL_CLOSED);
        assertThat(result.reason()).isNotBlank();
    }

    @Test
    void classifyRowSecurityReportsUnknownRatherThanSafeForAnUnparseableQuery() {
        var result = initializedCassandra()
                .classifyRowSecurity(request("this is not cql", tenantEquals("users")));
        assertThat(result.outcome()).isEqualTo(RowSecurityOutcome.UNKNOWN);
        assertThat(result.reason()).isNotBlank();
    }

    @Test
    void scyllaStampsItsOwnEngineIdOnTheInheritedClassification() {
        var engine = new ScyllaDbQueryEngine();
        engine.initialize(context());
        var result = engine.classifyRowSecurity(
                request("SELECT * FROM users WHERE id = 1", tenantEquals("users")));
        assertThat(result.engineId()).isEqualTo("scylladb");
        assertThat(result.outcome()).isEqualTo(RowSecurityOutcome.UNKNOWN);
    }

    @Test
    void classifyRowSecurityFailsBeforeInitialize() {
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> new CassandraQueryEngine().classifyRowSecurity(
                        request("SELECT * FROM users WHERE id = 1", tenantEquals("users"))))
                .isInstanceOf(IllegalStateException.class);
    }
}
