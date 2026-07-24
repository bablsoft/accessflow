package com.bablsoft.accessflow.core.api;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class QueryAffectedRowsResultTest {

    @Test
    void unsupportedCarriesEngineIdAndNoCount() {
        var result = QueryAffectedRowsResult.unsupported("redis");

        assertThat(result.supported()).isFalse();
        assertThat(result.engineId()).isEqualTo("redis");
        assertThat(result.affectedRows()).isNull();
        assertThat(result.duration()).isEqualTo(Duration.ZERO);
        assertThat(result.unsupportedReason()).isNull();
    }

    @Test
    void unsupportedWithReason() {
        var result = QueryAffectedRowsResult.unsupported("couchbase", "join shapes");

        assertThat(result.supported()).isFalse();
        assertThat(result.unsupportedReason()).isEqualTo("join shapes");
    }

    @Test
    void ofCarriesCountAndDuration() {
        var result = QueryAffectedRowsResult.of("postgresql", 90L, Duration.ofMillis(8));

        assertThat(result.supported()).isTrue();
        assertThat(result.engineId()).isEqualTo("postgresql");
        assertThat(result.affectedRows()).isEqualTo(90L);
        assertThat(result.duration()).isEqualTo(Duration.ofMillis(8));
    }
}
