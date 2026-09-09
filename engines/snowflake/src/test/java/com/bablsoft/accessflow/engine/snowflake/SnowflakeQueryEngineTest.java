package com.bablsoft.accessflow.engine.snowflake;

import com.bablsoft.accessflow.core.api.QueryEngineContext;
import com.bablsoft.accessflow.core.api.QueryEngineRowSecurityRequest;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.RowSecurityDirective;
import com.bablsoft.accessflow.core.api.RowSecurityOperator;
import com.bablsoft.accessflow.core.api.RowSecurityOutcome;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SnowflakeQueryEngineTest {

    private static QueryEngineContext context() {
        return new QueryEngineContext(TestMessages.keyEcho(), ciphertext -> ciphertext,
                Map.of(), Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void engineIdMatchesConnectorId() {
        assertThat(new SnowflakeQueryEngine().engineId()).isEqualTo("snowflake");
    }

    @Test
    void useBeforeInitializeFailsFast() {
        var engine = new SnowflakeQueryEngine();
        assertThatThrownBy(() -> engine.parse("SELECT 1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("before initialize");
    }

    @Test
    void dryRunBeforeInitializeFailsFast() {
        var engine = new SnowflakeQueryEngine();
        assertThatThrownBy(() -> engine.dryRun(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("before initialize");
    }

    @Test
    void initializeWiresTheParser() {
        var engine = new SnowflakeQueryEngine();
        engine.initialize(context());
        var result = engine.parse("SELECT * FROM orders WHERE id = 1");
        assertThat(result.type()).isEqualTo(QueryType.SELECT);
        assertThat(result.referencedTables()).containsExactly("orders");
    }

    @Test
    void initializeRejectsNullContext() {
        var engine = new SnowflakeQueryEngine();
        assertThatThrownBy(() -> engine.initialize(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void evictAndShutdownAreNoOpsEvenBeforeInitialize() {
        var engine = new SnowflakeQueryEngine();
        assertThatCode(() -> {
            engine.evictDatasource(UUID.randomUUID());
            engine.shutdown();
        }).doesNotThrowAnyException();
    }

    // ---- offline row-security classification (AF-630) -------------------------------------------

    private static SnowflakeQueryEngine initialized() {
        var engine = new SnowflakeQueryEngine();
        engine.initialize(context());
        return engine;
    }

    private static QueryEngineRowSecurityRequest classifyRequest(String query,
                                                                 RowSecurityDirective... directives) {
        return new QueryEngineRowSecurityRequest(UUID.randomUUID(), query, List.of(directives));
    }

    private static RowSecurityDirective tenantEquals(String table) {
        return new RowSecurityDirective(UUID.randomUUID(), table, "tenant",
                RowSecurityOperator.EQUALS, List.of("acme"));
    }

    @Test
    void classifyRowSecurityShortCircuitsWhenThereAreNoDirectives() {
        var result = initialized().classifyRowSecurity(classifyRequest("SELECT * FROM orders"));
        assertThat(result.outcome()).isEqualTo(RowSecurityOutcome.NOT_APPLICABLE);
        assertThat(result.engineId()).isEqualTo("snowflake");
    }

    @Test
    void classifyRowSecurityDelegatesToTheApplier() {
        var directive = tenantEquals("orders");
        var result = initialized()
                .classifyRowSecurity(classifyRequest("SELECT * FROM orders", directive));
        assertThat(result.outcome()).isEqualTo(RowSecurityOutcome.APPLIED);
        assertThat(result.appliedPolicyIds()).containsExactly(directive.policyId());
    }

    @Test
    void classifyRowSecurityReportsFailClosedForAPoliciedInsert() {
        var result = initialized().classifyRowSecurity(
                classifyRequest("INSERT INTO orders VALUES (1)", tenantEquals("orders")));
        assertThat(result.outcome()).isEqualTo(RowSecurityOutcome.FAIL_CLOSED);
        assertThat(result.reason()).isNotBlank();
    }

    @Test
    void classifyRowSecurityReportsUnknownRatherThanSafeForAnUnparseableQuery() {
        var result = initialized().classifyRowSecurity(
                classifyRequest("GRANT SELECT ON orders TO analyst", tenantEquals("orders")));
        assertThat(result.outcome()).isEqualTo(RowSecurityOutcome.UNKNOWN);
        assertThat(result.reason()).isNotBlank();
    }

    @Test
    void classifyRowSecurityBeforeInitializeFailsFast() {
        assertThatThrownBy(() -> new SnowflakeQueryEngine()
                .classifyRowSecurity(classifyRequest("SELECT * FROM orders", tenantEquals("orders"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("before initialize");
    }
}
