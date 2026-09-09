package com.bablsoft.accessflow.engine.dynamodb;

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
 * Container-free unit tests for the {@link DynamoDbQueryEngine} SPI facade — the offline
 * row-security classification path (AF-630), which must answer without a client or a connection.
 * The connected surface is covered by {@code DynamoDbQueryEngineIntegrationTest}.
 */
class DynamoDbQueryEngineTest {

    private static DynamoDbQueryEngine initialized() {
        var engine = new DynamoDbQueryEngine();
        engine.initialize(new QueryEngineContext(
                TestMessages.keyEcho(), ciphertext -> ciphertext, Map.of(), Clock.systemUTC()));
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
        var result = initialized().classifyRowSecurity(classifyRequest("SELECT * FROM \"Users\""));
        assertThat(result.outcome()).isEqualTo(RowSecurityOutcome.NOT_APPLICABLE);
        assertThat(result.engineId()).isEqualTo("dynamodb");
    }

    @Test
    void classifyRowSecurityReportsNotApplicableWhenNoDirectiveTargetsTheTable() {
        var result = initialized().classifyRowSecurity(
                classifyRequest("SELECT * FROM \"Users\"", tenantEquals("Orders")));
        assertThat(result.outcome()).isEqualTo(RowSecurityOutcome.NOT_APPLICABLE);
    }

    @Test
    void classifyRowSecurityDelegatesToTheApplier() {
        var directive = tenantEquals("Users");
        var result = initialized()
                .classifyRowSecurity(classifyRequest("SELECT * FROM \"Users\"", directive));
        assertThat(result.outcome()).isEqualTo(RowSecurityOutcome.APPLIED);
        assertThat(result.appliedPolicyIds()).containsExactly(directive.policyId());
    }

    @Test
    void classifyRowSecurityReportsDenyAllForADirectiveWithNoValues() {
        var directive = new RowSecurityDirective(UUID.randomUUID(), "Users", "tenant",
                RowSecurityOperator.IN, List.of());
        var result = initialized()
                .classifyRowSecurity(classifyRequest("SELECT * FROM \"Users\"", directive));
        assertThat(result.outcome()).isEqualTo(RowSecurityOutcome.DENY_ALL);
        assertThat(result.appliedPolicyIds()).containsExactly(directive.policyId());
    }

    @Test
    void classifyRowSecurityReportsFailClosedForAPoliciedInsert() {
        var result = initialized().classifyRowSecurity(classifyRequest(
                "INSERT INTO \"Users\" VALUE {'id': 'u1'}", tenantEquals("Users")));
        assertThat(result.outcome()).isEqualTo(RowSecurityOutcome.FAIL_CLOSED);
        assertThat(result.reason()).isNotBlank();
    }

    @Test
    void classifyRowSecurityReportsUnknownRatherThanSafeForAnUnparseableQuery() {
        var result = initialized().classifyRowSecurity(
                classifyRequest("EXECUTE TRANSACTION", tenantEquals("Users")));
        assertThat(result.outcome()).isEqualTo(RowSecurityOutcome.UNKNOWN);
        assertThat(result.reason()).isNotBlank();
        assertThat(result.appliedPolicyIds()).isEmpty();
    }

    @Test
    void classifyRowSecurityFailsBeforeInitialize() {
        assertThatThrownBy(() -> new DynamoDbQueryEngine()
                .classifyRowSecurity(classifyRequest("SELECT * FROM \"Users\"", tenantEquals("Users"))))
                .isInstanceOf(IllegalStateException.class);
    }
}
