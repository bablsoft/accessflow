package com.bablsoft.accessflow.engine.cassandra;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CassandraEngineSettingsTest {

    @Test
    void usesDefaultsForEmptyConfig() {
        var settings = CassandraEngineSettings.from(Map.of());
        assertThat(settings.connectTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(settings.requestTimeout()).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void usesDefaultsForNullConfig() {
        var settings = CassandraEngineSettings.from(null);
        assertThat(settings.connectTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(settings.requestTimeout()).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void parsesIso8601Durations() {
        var settings = CassandraEngineSettings.from(Map.of(
                "connect-timeout", "PT3S", "request-timeout", "PT45S"));
        assertThat(settings.connectTimeout()).isEqualTo(Duration.ofSeconds(3));
        assertThat(settings.requestTimeout()).isEqualTo(Duration.ofSeconds(45));
    }

    @Test
    void fallsBackOnUnparseableOrNonPositiveValues() {
        var settings = CassandraEngineSettings.from(Map.of(
                "connect-timeout", "nonsense", "request-timeout", "PT0S"));
        assertThat(settings.connectTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(settings.requestTimeout()).isEqualTo(Duration.ofSeconds(10));
    }
}
