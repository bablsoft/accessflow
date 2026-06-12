package com.bablsoft.accessflow.engine.elasticsearch;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ElasticsearchEngineSettingsTest {

    @Test
    void usesDefaultsWhenConfigIsEmptyOrNull() {
        var settings = ElasticsearchEngineSettings.from(null);
        assertThat(settings.connectTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(settings.socketTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(settings.adminConnectTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(settings.adminSocketTimeout()).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    void parsesIso8601Durations() {
        var settings = ElasticsearchEngineSettings.from(
                Map.of("connect-timeout", "PT3S", "socket-timeout", "PT45S"));
        assertThat(settings.connectTimeout()).isEqualTo(Duration.ofSeconds(3));
        assertThat(settings.socketTimeout()).isEqualTo(Duration.ofSeconds(45));
    }

    @Test
    void fallsBackToDefaultsOnGarbageOrNonPositiveValues() {
        var settings = ElasticsearchEngineSettings.from(
                Map.of("connect-timeout", "nonsense", "socket-timeout", "PT0S"));
        assertThat(settings.connectTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(settings.socketTimeout()).isEqualTo(Duration.ofSeconds(30));
    }
}
