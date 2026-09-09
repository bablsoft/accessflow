package com.bablsoft.accessflow.engine.databricks;

import com.bablsoft.accessflow.core.api.QueryEngineContext;
import com.bablsoft.accessflow.core.api.QueryEngineRowSecurityRequest;
import com.bablsoft.accessflow.core.api.RowSecurityDirective;
import com.bablsoft.accessflow.core.api.RowSecurityOperator;
import com.bablsoft.accessflow.core.api.RowSecurityOutcome;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit-level cover for the engine entry points that answer without an HTTP client. Offline
 * row-security classification (issue AF-630) belongs here precisely because it must never open a
 * connection; the rest of the SPI is exercised by {@code DatabricksQueryEngineIntegrationTest}.
 */
class DatabricksQueryEngineTest {

    private static DatabricksQueryEngine initialized() {
        var engine = new DatabricksQueryEngine();
        engine.initialize(new QueryEngineContext(
                TestMessages.keyEcho(), ciphertext -> ciphertext, Map.of(), Clock.systemUTC()));
        return engine;
    }

    private static QueryEngineRowSecurityRequest request(String query,
                                                         RowSecurityDirective... directives) {
        return new QueryEngineRowSecurityRequest(UUID.randomUUID(), query, List.of(directives));
    }

    private static RowSecurityDirective tenantEquals(String table) {
        return new RowSecurityDirective(UUID.randomUUID(), table, "tenant",
                RowSecurityOperator.EQUALS, List.of("acme"));
    }

    @Test
    void engineIdIsTheConnectorId() {
        assertThat(new DatabricksQueryEngine().engineId()).isEqualTo("databricks");
    }

    @Test
    void classifyRowSecurityShortCircuitsWhenThereAreNoDirectives() {
        var result = initialized().classifyRowSecurity(request("SELECT * FROM orders"));
        assertThat(result.outcome()).isEqualTo(RowSecurityOutcome.NOT_APPLICABLE);
        assertThat(result.engineId()).isEqualTo("databricks");
    }

    @Test
    void classifyRowSecurityReportsNotApplicableForAnUnrelatedTable() {
        var result = initialized()
                .classifyRowSecurity(request("SELECT * FROM orders", tenantEquals("refunds")));
        assertThat(result.outcome()).isEqualTo(RowSecurityOutcome.NOT_APPLICABLE);
    }

    @Test
    void classifyRowSecurityDelegatesToTheApplier() {
        var directive = tenantEquals("orders");
        var result = initialized().classifyRowSecurity(request("SELECT * FROM orders", directive));
        assertThat(result.outcome()).isEqualTo(RowSecurityOutcome.APPLIED);
        assertThat(result.appliedPolicyIds()).containsExactly(directive.policyId());
    }

    @Test
    void classifyRowSecurityReportsFailClosedForAPoliciedInsert() {
        var result = initialized().classifyRowSecurity(
                request("INSERT INTO orders VALUES (1)", tenantEquals("orders")));
        assertThat(result.outcome()).isEqualTo(RowSecurityOutcome.FAIL_CLOSED);
        assertThat(result.reason()).isNotBlank();
    }

    @Test
    void classifyRowSecurityReportsUnknownRatherThanSafeForAnUnparseableQuery() {
        var result = initialized()
                .classifyRowSecurity(request("this is not sql", tenantEquals("orders")));
        assertThat(result.outcome()).isEqualTo(RowSecurityOutcome.UNKNOWN);
        assertThat(result.reason()).isNotBlank();
    }

    @Test
    void classifyRowSecurityFailsBeforeInitialize() {
        assertThatThrownBy(() -> new DatabricksQueryEngine()
                .classifyRowSecurity(request("SELECT * FROM orders", tenantEquals("orders"))))
                .isInstanceOf(IllegalStateException.class);
    }
}
