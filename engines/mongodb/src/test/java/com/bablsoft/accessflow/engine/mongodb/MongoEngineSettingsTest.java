package com.bablsoft.accessflow.engine.mongodb;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MongoEngineSettingsTest {

    @Test
    void appliesDefaultsWhenConfigEmpty() {
        var settings = MongoEngineSettings.from(Map.of());
        assertThat(settings.connectTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(settings.serverSelectionTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(settings.maxPoolSize()).isEqualTo(10);
    }

    @Test
    void honoursExplicitValuesAndMapsToOptions() {
        var settings = MongoEngineSettings.from(Map.of(
                "connect-timeout", "PT3S",
                "server-selection-timeout", "PT7S",
                "max-pool-size", "25"));
        var options = settings.toOptions();
        assertThat(options.connectTimeout()).isEqualTo(Duration.ofSeconds(3));
        assertThat(options.serverSelectionTimeout()).isEqualTo(Duration.ofSeconds(7));
        assertThat(options.maxPoolSize()).isEqualTo(25);
    }

    @Test
    void fallsBackToDefaultsOnUnparseableValues() {
        var settings = MongoEngineSettings.from(Map.of(
                "connect-timeout", "ten seconds",
                "server-selection-timeout", " ",
                "max-pool-size", "lots"));
        assertThat(settings.connectTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(settings.serverSelectionTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(settings.maxPoolSize()).isEqualTo(10);
    }
}
