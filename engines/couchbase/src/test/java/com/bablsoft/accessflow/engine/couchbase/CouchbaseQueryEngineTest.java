package com.bablsoft.accessflow.engine.couchbase;

import com.bablsoft.accessflow.core.api.DatasourceConnectionDescriptor;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.QueryEngineContext;
import com.bablsoft.accessflow.core.api.QueryEngineDryRunRequest;
import com.bablsoft.accessflow.core.api.QueryEngineRowSecurityRequest;
import com.bablsoft.accessflow.core.api.QueryExecutionRequest;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.RowSecurityDirective;
import com.bablsoft.accessflow.core.api.RowSecurityOperator;
import com.bablsoft.accessflow.core.api.RowSecurityOutcome;
import com.bablsoft.accessflow.core.api.SslMode;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CouchbaseQueryEngineTest {

    @Test
    void engineIdMatchesConnectorIdAndFolderName() {
        assertThat(new CouchbaseQueryEngine().engineId()).isEqualTo("couchbase");
    }

    @Test
    void usingTheEngineBeforeInitializeFails() {
        var engine = new CouchbaseQueryEngine();
        assertThatThrownBy(() -> engine.parse("SELECT 1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("before initialize");
        assertThatThrownBy(() -> engine.countAffectedRows(dryRunRequest()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("before initialize");
        // Eviction and shutdown before initialize are safe no-ops.
        engine.evictDatasource(UUID.randomUUID());
        engine.shutdown();
    }

    private static QueryEngineDryRunRequest dryRunRequest() {
        var request = new QueryExecutionRequest(UUID.randomUUID(), "DELETE FROM users",
                QueryType.DELETE, null, null);
        var descriptor = new DatasourceConnectionDescriptor(UUID.randomUUID(), UUID.randomUUID(),
                DbType.COUCHBASE, "127.0.0.1", 1, "bucket", "user", "", SslMode.DISABLE, 10,
                1000, true, null, false, null, "couchbase", null, null, null, null, true);
        return new QueryEngineDryRunRequest(request, descriptor, Duration.ofSeconds(5));
    }

    @Test
    void initializeWiresTheParser() {
        var engine = new CouchbaseQueryEngine();
        engine.initialize(new QueryEngineContext(
                TestMessages.keyEcho(), ciphertext -> ciphertext, Map.of(), Clock.systemUTC()));
        assertThat(engine.parse("SELECT 1").statements()).containsExactly("SELECT 1");
    }

    // ---- offline row-security classification (AF-630) -------------------------------------------

    private static CouchbaseQueryEngine initialized() {
        var engine = new CouchbaseQueryEngine();
        engine.initialize(new QueryEngineContext(
                TestMessages.keyEcho(), ciphertext -> ciphertext, Map.of(), Clock.systemUTC()));
        return engine;
    }

    private static QueryEngineRowSecurityRequest classifyRequest(String query,
                                                                 RowSecurityDirective... directives) {
        return new QueryEngineRowSecurityRequest(UUID.randomUUID(), query, List.of(directives));
    }

    private static RowSecurityDirective teamEquals(String keyspace) {
        return new RowSecurityDirective(UUID.randomUUID(), keyspace, "team",
                RowSecurityOperator.EQUALS, List.of("eng"));
    }

    @Test
    void classifyRowSecurityShortCircuitsWhenThereAreNoDirectives() {
        var result = initialized().classifyRowSecurity(classifyRequest("SELECT * FROM users"));
        assertThat(result.outcome()).isEqualTo(RowSecurityOutcome.NOT_APPLICABLE);
        assertThat(result.engineId()).isEqualTo("couchbase");
    }

    @Test
    void classifyRowSecurityDelegatesToTheApplier() {
        var directive = teamEquals("users");
        var result = initialized()
                .classifyRowSecurity(classifyRequest("SELECT * FROM users", directive));
        assertThat(result.outcome()).isEqualTo(RowSecurityOutcome.APPLIED);
        assertThat(result.appliedPolicyIds()).containsExactly(directive.policyId());
    }

    @Test
    void classifyRowSecurityReportsFailClosedForAPoliciedInsert() {
        var result = initialized().classifyRowSecurity(classifyRequest(
                "INSERT INTO users (KEY, VALUE) VALUES ('k', {'a': 1})", teamEquals("users")));
        assertThat(result.outcome()).isEqualTo(RowSecurityOutcome.FAIL_CLOSED);
        assertThat(result.reason()).isNotBlank();
    }

    @Test
    void classifyRowSecurityReportsUnknownRatherThanSafeForAnUnparseableQuery() {
        var result = initialized()
                .classifyRowSecurity(classifyRequest("not a sql++ statement", teamEquals("users")));
        assertThat(result.outcome()).isEqualTo(RowSecurityOutcome.UNKNOWN);
        assertThat(result.reason()).isNotBlank();
    }

    @Test
    void classifyRowSecurityFailsBeforeInitialize() {
        assertThatThrownBy(() -> new CouchbaseQueryEngine()
                .classifyRowSecurity(classifyRequest("SELECT * FROM users", teamEquals("users"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("before initialize");
    }
}
