package com.bablsoft.accessflow.core.api;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class QueryDryRunResultTest {

    @Test
    void unsupportedFactoryMarksNotSupportedWithNullReason() {
        var result = QueryDryRunResult.unsupported("redis");
        assertThat(result.supported()).isFalse();
        assertThat(result.engineId()).isEqualTo("redis");
        assertThat(result.unsupportedReason()).isNull();
        assertThat(result.plan()).isNull();
        assertThat(result.estimatedRows()).isNull();
        assertThat(result.appliedRowSecurityPolicyIds()).isEmpty();
    }

    @Test
    void unsupportedFactoryCarriesEngineReason() {
        var result = QueryDryRunResult.unsupported("mongodb", "INSERT has no plan");
        assertThat(result.supported()).isFalse();
        assertThat(result.unsupportedReason()).isEqualTo("INSERT has no plan");
    }

    @Test
    void ofFactoryMarksSupported() {
        var id = UUID.randomUUID();
        var plan = new QueryPlanNode("Seq Scan", "users", 100.0, 12.5, null);
        var result = QueryDryRunResult.of("postgresql", QueryType.SELECT, 100L, plan, "{...}",
                Set.of(id), Duration.ofMillis(5));
        assertThat(result.supported()).isTrue();
        assertThat(result.engineId()).isEqualTo("postgresql");
        assertThat(result.queryType()).isEqualTo(QueryType.SELECT);
        assertThat(result.estimatedRows()).isEqualTo(100L);
        assertThat(result.plan()).isSameAs(plan);
        assertThat(result.rawPlan()).isEqualTo("{...}");
        assertThat(result.appliedRowSecurityPolicyIds()).containsExactly(id);
        assertThat(result.unsupportedReason()).isNull();
        assertThat(result.estimatedBytesScanned()).isNull();
    }

    @Test
    void nullRowSecurityIdsBecomesEmpty() {
        var result = new QueryDryRunResult(true, "postgresql", QueryType.SELECT, 1L, null, null,
                null, null, Duration.ZERO, null);
        assertThat(result.appliedRowSecurityPolicyIds()).isEmpty();
    }

    @Test
    void withUnsupportedReasonCopiesAndSetsReason() {
        var copied = QueryDryRunResult.unsupported("cassandra").withUnsupportedReason("no EXPLAIN");
        assertThat(copied.supported()).isFalse();
        assertThat(copied.engineId()).isEqualTo("cassandra");
        assertThat(copied.unsupportedReason()).isEqualTo("no EXPLAIN");
    }

    @Test
    void withEstimatedBytesScannedCopiesAllOtherFields() {
        var id = UUID.randomUUID();
        var result = QueryDryRunResult.of("bigquery", QueryType.SELECT, 100L, null, "raw",
                Set.of(id), Duration.ofMillis(5)).withEstimatedBytesScanned(1_048_576L);
        assertThat(result.estimatedBytesScanned()).isEqualTo(1_048_576L);
        assertThat(result.supported()).isTrue();
        assertThat(result.engineId()).isEqualTo("bigquery");
        assertThat(result.queryType()).isEqualTo(QueryType.SELECT);
        assertThat(result.estimatedRows()).isEqualTo(100L);
        assertThat(result.rawPlan()).isEqualTo("raw");
        assertThat(result.appliedRowSecurityPolicyIds()).containsExactly(id);
        assertThat(result.unsupportedReason()).isNull();
    }

    @Test
    void withUnsupportedReasonPreservesEstimatedBytesScanned() {
        var result = QueryDryRunResult.of("bigquery", QueryType.SELECT, null, null, null,
                Set.of(), Duration.ZERO).withEstimatedBytesScanned(42L)
                .withUnsupportedReason("reason");
        assertThat(result.estimatedBytesScanned()).isEqualTo(42L);
        assertThat(result.unsupportedReason()).isEqualTo("reason");
    }

    @Test
    void unsupportedFactoryHasNullEstimatedBytesScanned() {
        assertThat(QueryDryRunResult.unsupported("redis").estimatedBytesScanned()).isNull();
        assertThat(QueryDryRunResult.unsupported("redis", "why").estimatedBytesScanned()).isNull();
    }
}
