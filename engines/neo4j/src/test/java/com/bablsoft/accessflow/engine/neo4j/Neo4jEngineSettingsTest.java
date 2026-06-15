package com.bablsoft.accessflow.engine.neo4j;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class Neo4jEngineSettingsTest {

    @Test
    void usesDefaultsForNullOrEmptyConfig() {
        var settings = Neo4jEngineSettings.from(null);
        assertThat(settings.connectTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(settings.maxConnectionPoolSize()).isEqualTo(100);
    }

    @Test
    void parsesProvidedValues() {
        var settings = Neo4jEngineSettings.from(Map.of(
                "connect-timeout", "PT3S",
                "max-connection-pool-size", "50"));
        assertThat(settings.connectTimeout()).isEqualTo(Duration.ofSeconds(3));
        assertThat(settings.maxConnectionPoolSize()).isEqualTo(50);
    }

    @Test
    void fallsBackOnUnparseableOrNonPositiveValues() {
        var settings = Neo4jEngineSettings.from(Map.of(
                "connect-timeout", "nonsense",
                "max-connection-pool-size", "-1"));
        assertThat(settings.connectTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(settings.maxConnectionPoolSize()).isEqualTo(100);
    }

    @Test
    void rejectsZeroAndNegativeDurations() {
        assertThat(Neo4jEngineSettings.from(Map.of("connect-timeout", "PT0S")).connectTimeout())
                .isEqualTo(Duration.ofSeconds(10));
        assertThat(Neo4jEngineSettings.from(Map.of("connect-timeout", "PT-5S")).connectTimeout())
                .isEqualTo(Duration.ofSeconds(10));
    }
}
