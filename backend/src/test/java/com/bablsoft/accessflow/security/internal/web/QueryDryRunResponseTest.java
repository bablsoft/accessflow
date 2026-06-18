package com.bablsoft.accessflow.security.internal.web;

import com.bablsoft.accessflow.core.api.QueryDryRunResult;
import com.bablsoft.accessflow.core.api.QueryPlanNode;
import com.bablsoft.accessflow.core.api.QueryType;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class QueryDryRunResponseTest {

    @Test
    void mapsSupportedResultWithNestedPlanTree() {
        var plan = new QueryPlanNode("Nested Loop", null, 100.0, 25.0, null,
                List.of(new QueryPlanNode("Seq Scan", "users", 100.0, 12.5, "(age > 21)")));
        var result = QueryDryRunResult.of("postgresql", QueryType.SELECT, 100L, plan, "{...}",
                Set.of(), Duration.ofMillis(7));

        var response = QueryDryRunResponse.from(result);

        assertThat(response.supported()).isTrue();
        assertThat(response.engineId()).isEqualTo("postgresql");
        assertThat(response.queryType()).isEqualTo(QueryType.SELECT);
        assertThat(response.estimatedRows()).isEqualTo(100L);
        assertThat(response.rawPlan()).isEqualTo("{...}");
        assertThat(response.durationMs()).isEqualTo(7L);
        assertThat(response.plan().operation()).isEqualTo("Nested Loop");
        assertThat(response.plan().children()).hasSize(1);
        assertThat(response.plan().children().getFirst().operation()).isEqualTo("Seq Scan");
        assertThat(response.plan().children().getFirst().detail()).isEqualTo("(age > 21)");
    }

    @Test
    void mapsUnsupportedResultWithNullPlan() {
        var result = QueryDryRunResult.unsupported("redis", "not supported");

        var response = QueryDryRunResponse.from(result);

        assertThat(response.supported()).isFalse();
        assertThat(response.plan()).isNull();
        assertThat(response.estimatedRows()).isNull();
        assertThat(response.unsupportedReason()).isEqualTo("not supported");
        assertThat(response.durationMs()).isZero();
    }
}
