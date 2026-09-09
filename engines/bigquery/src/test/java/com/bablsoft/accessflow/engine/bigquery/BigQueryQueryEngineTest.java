package com.bablsoft.accessflow.engine.bigquery;

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
 * Unit-level cover for the engine entry points that answer without a client. The container-backed
 * {@code BigQueryQueryEngineIntegrationTest} exercises the rest; offline row-security
 * classification (issue AF-630) belongs here precisely because it must never open a connection.
 */
class BigQueryQueryEngineTest {

    private static BigQueryQueryEngine initialized() {
        var engine = new BigQueryQueryEngine();
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
        assertThat(new BigQueryQueryEngine().engineId()).isEqualTo("bigquery");
    }

    @Test
    void classifyRowSecurityShortCircuitsWhenThereAreNoDirectives() {
        var result = initialized().classifyRowSecurity(request("SELECT * FROM ds.users"));
        assertThat(result.outcome()).isEqualTo(RowSecurityOutcome.NOT_APPLICABLE);
        assertThat(result.engineId()).isEqualTo("bigquery");
    }

    @Test
    void classifyRowSecurityReportsNotApplicableForAnUnrelatedTable() {
        var result = initialized()
                .classifyRowSecurity(request("SELECT * FROM ds.users", tenantEquals("ds.orders")));
        assertThat(result.outcome()).isEqualTo(RowSecurityOutcome.NOT_APPLICABLE);
    }

    @Test
    void classifyRowSecurityDelegatesToTheApplier() {
        var directive = tenantEquals("ds.users");
        var result = initialized()
                .classifyRowSecurity(request("SELECT * FROM ds.users", directive));
        assertThat(result.outcome()).isEqualTo(RowSecurityOutcome.APPLIED);
        assertThat(result.appliedPolicyIds()).containsExactly(directive.policyId());
    }

    @Test
    void classifyRowSecurityReportsFailClosedForAPoliciedInsert() {
        var result = initialized().classifyRowSecurity(
                request("INSERT INTO ds.users (id) VALUES (1)", tenantEquals("ds.users")));
        assertThat(result.outcome()).isEqualTo(RowSecurityOutcome.FAIL_CLOSED);
        assertThat(result.reason()).isNotBlank();
    }

    @Test
    void classifyRowSecurityReportsUnknownRatherThanSafeForAnUnparseableQuery() {
        var result = initialized()
                .classifyRowSecurity(request("this is not sql", tenantEquals("ds.users")));
        assertThat(result.outcome()).isEqualTo(RowSecurityOutcome.UNKNOWN);
        assertThat(result.reason()).isNotBlank();
    }

    @Test
    void classifyRowSecurityFailsBeforeInitialize() {
        assertThatThrownBy(() -> new BigQueryQueryEngine()
                .classifyRowSecurity(request("SELECT * FROM ds.users", tenantEquals("ds.users"))))
                .isInstanceOf(IllegalStateException.class);
    }
}
