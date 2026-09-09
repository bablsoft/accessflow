package com.bablsoft.accessflow.engine.redis;

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

class RedisQueryEngineTest {

    private static RedisQueryEngine initialized() {
        var engine = new RedisQueryEngine();
        engine.initialize(new QueryEngineContext(
                TestMessages.of(Map.of()), ciphertext -> ciphertext, Map.of(), Clock.systemUTC()));
        return engine;
    }

    private static QueryEngineRowSecurityRequest request(String query,
                                                         RowSecurityDirective... directives) {
        return new QueryEngineRowSecurityRequest(UUID.randomUUID(), query, List.of(directives));
    }

    private static RowSecurityDirective directive(String tableRef) {
        return new RowSecurityDirective(UUID.randomUUID(), tableRef, "tenant",
                RowSecurityOperator.EQUALS, List.of("acme"));
    }

    @Test
    void engineIdIsTheConnectorId() {
        assertThat(new RedisQueryEngine().engineId()).isEqualTo("redis");
    }

    @Test
    void classifyRowSecurityShortCircuitsWhenThereAreNoDirectives() {
        var result = initialized().classifyRowSecurity(request("GET session:42"));
        assertThat(result.outcome()).isEqualTo(RowSecurityOutcome.NOT_APPLICABLE);
        assertThat(result.engineId()).isEqualTo("redis");
    }

    @Test
    void classifyRowSecurityReportsNotApplicableForAnUnrelatedPrefix() {
        var result = initialized().classifyRowSecurity(request("GET session:42", directive("orders")));
        assertThat(result.outcome()).isEqualTo(RowSecurityOutcome.NOT_APPLICABLE);
    }

    @Test
    void classifyRowSecurityAlwaysFailsClosedOnAPoliciedPrefix() {
        var result = initialized().classifyRowSecurity(request("GET session:42", directive("session")));
        assertThat(result.outcome()).isEqualTo(RowSecurityOutcome.FAIL_CLOSED);
        assertThat(result.reason()).isNotBlank();
    }

    @Test
    void classifyRowSecurityReportsUnknownRatherThanSafeForAnUnparseableCommand() {
        var result = initialized().classifyRowSecurity(request("", directive("session")));
        assertThat(result.outcome()).isEqualTo(RowSecurityOutcome.UNKNOWN);
        assertThat(result.reason()).isNotBlank();
    }

    @Test
    void classifyRowSecurityFailsBeforeInitialize() {
        assertThatThrownBy(() -> new RedisQueryEngine()
                .classifyRowSecurity(request("GET session:42", directive("session"))))
                .isInstanceOf(IllegalStateException.class);
    }
}
