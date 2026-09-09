package com.bablsoft.accessflow.engine.neo4j;

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
 * Connection-free unit coverage of the {@link Neo4jQueryEngine} SPI facade — the offline
 * row-security classification the policy simulator calls (issue AF-630), which must never open a
 * driver or a session. The connected surface is covered by {@code Neo4jQueryEngineIntegrationTest}.
 */
class Neo4jQueryEngineTest {

    private static Neo4jQueryEngine initialized() {
        var engine = new Neo4jQueryEngine();
        engine.initialize(new QueryEngineContext(
                TestMessages.keyEcho(), ciphertext -> ciphertext, Map.of(), Clock.systemUTC()));
        return engine;
    }

    private static QueryEngineRowSecurityRequest classifyRequest(String query,
                                                                 RowSecurityDirective... directives) {
        return new QueryEngineRowSecurityRequest(UUID.randomUUID(), query, List.of(directives));
    }

    private static RowSecurityDirective regionEquals(String label) {
        return new RowSecurityDirective(UUID.randomUUID(), label, "region",
                RowSecurityOperator.EQUALS, List.of("EU"));
    }

    @Test
    void classifyRowSecurityShortCircuitsWhenThereAreNoDirectives() {
        var result = initialized().classifyRowSecurity(classifyRequest("MATCH (u:User) RETURN u"));
        assertThat(result.outcome()).isEqualTo(RowSecurityOutcome.NOT_APPLICABLE);
        assertThat(result.engineId()).isEqualTo("neo4j");
    }

    @Test
    void classifyRowSecurityDelegatesToTheApplier() {
        var directive = regionEquals("User");
        var result = initialized()
                .classifyRowSecurity(classifyRequest("MATCH (u:User) RETURN u", directive));
        assertThat(result.outcome()).isEqualTo(RowSecurityOutcome.APPLIED);
        assertThat(result.appliedPolicyIds()).containsExactly(directive.policyId());
    }

    @Test
    void classifyRowSecurityReportsFailClosedForAPoliciedCreate() {
        var result = initialized().classifyRowSecurity(
                classifyRequest("CREATE (u:User {id: 1})", regionEquals("User")));
        assertThat(result.outcome()).isEqualTo(RowSecurityOutcome.FAIL_CLOSED);
        assertThat(result.reason()).isNotBlank();
    }

    @Test
    void classifyRowSecurityReportsUnknownRatherThanSafeForAnUnparseableQuery() {
        var result = initialized().classifyRowSecurity(
                classifyRequest("MATCH (u:User) RETURN u; MATCH (v:User) RETURN v",
                        regionEquals("User")));
        assertThat(result.outcome()).isEqualTo(RowSecurityOutcome.UNKNOWN);
        assertThat(result.reason()).isNotBlank();
    }

    @Test
    void classifyRowSecurityFailsBeforeInitialize() {
        assertThatThrownBy(() -> new Neo4jQueryEngine()
                .classifyRowSecurity(classifyRequest("MATCH (u:User) RETURN u", regionEquals("User"))))
                .isInstanceOf(IllegalStateException.class);
    }
}
