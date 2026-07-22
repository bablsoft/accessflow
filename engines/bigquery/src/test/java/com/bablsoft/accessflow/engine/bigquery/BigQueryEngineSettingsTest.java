package com.bablsoft.accessflow.engine.bigquery;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BigQueryEngineSettingsTest {

    @Test
    void defaultsWhenConfigIsNullOrEmpty() {
        for (var settings : new BigQueryEngineSettings[]{
                BigQueryEngineSettings.from(null),
                BigQueryEngineSettings.from(Map.of())}) {
            assertThat(settings.connectTimeout()).isEqualTo(Duration.ofSeconds(10));
            assertThat(settings.readTimeout()).isEqualTo(Duration.ofSeconds(30));
        }
    }

    @Test
    void parsesIsoDurations() {
        var settings = BigQueryEngineSettings.from(Map.of(
                "connect-timeout", "PT5S",
                "read-timeout", "PT2M"));
        assertThat(settings.connectTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(settings.readTimeout()).isEqualTo(Duration.ofMinutes(2));
    }

    @Test
    void unparseableBlankZeroOrNegativeValuesFallBackToDefaults() {
        var settings = BigQueryEngineSettings.from(Map.of(
                "connect-timeout", "nonsense",
                "read-timeout", "PT0S"));
        assertThat(settings.connectTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(settings.readTimeout()).isEqualTo(Duration.ofSeconds(30));

        var blankAndNegative = BigQueryEngineSettings.from(Map.of(
                "connect-timeout", " ",
                "read-timeout", "PT-5S"));
        assertThat(blankAndNegative.connectTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(blankAndNegative.readTimeout()).isEqualTo(Duration.ofSeconds(30));
    }
}
