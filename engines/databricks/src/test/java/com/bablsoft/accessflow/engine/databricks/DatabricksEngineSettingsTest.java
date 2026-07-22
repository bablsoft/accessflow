package com.bablsoft.accessflow.engine.databricks;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DatabricksEngineSettingsTest {

    @Test
    void defaultsWhenConfigMissingOrEmpty() {
        for (var settings : new DatabricksEngineSettings[]{
                DatabricksEngineSettings.from(null), DatabricksEngineSettings.from(Map.of())}) {
            assertThat(settings.connectTimeout()).isEqualTo(Duration.ofSeconds(10));
            assertThat(settings.waitTimeout()).isEqualTo(Duration.ofSeconds(10));
            assertThat(settings.pollInterval()).isEqualTo(Duration.ofSeconds(1));
        }
    }

    @Test
    void parsesIsoDurations() {
        var settings = DatabricksEngineSettings.from(Map.of(
                "connect-timeout", "PT5S",
                "wait-timeout", "PT20S",
                "poll-interval", "PT0.2S"));
        assertThat(settings.connectTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(settings.waitTimeout()).isEqualTo(Duration.ofSeconds(20));
        assertThat(settings.pollInterval()).isEqualTo(Duration.ofMillis(200));
    }

    @Test
    void fallsBackOnUnparseableBlankZeroOrNegative() {
        var settings = DatabricksEngineSettings.from(Map.of(
                "connect-timeout", "nonsense",
                "wait-timeout", " ",
                "poll-interval", "PT0S"));
        assertThat(settings.connectTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(settings.waitTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(settings.pollInterval()).isEqualTo(Duration.ofSeconds(1));
        assertThat(DatabricksEngineSettings.from(Map.of("poll-interval", "PT-1S")).pollInterval())
                .isEqualTo(Duration.ofSeconds(1));
    }

    @Test
    void clampsWaitTimeoutToTheApiWindow() {
        assertThat(DatabricksEngineSettings.from(Map.of("wait-timeout", "PT2S")).waitTimeout())
                .isEqualTo(Duration.ofSeconds(5));
        assertThat(DatabricksEngineSettings.from(Map.of("wait-timeout", "PT99S")).waitTimeout())
                .isEqualTo(Duration.ofSeconds(50));
        assertThat(DatabricksEngineSettings.from(Map.of("wait-timeout", "PT30S")).waitTimeout())
                .isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void formatsWaitTimeoutForTheApi() {
        assertThat(DatabricksEngineSettings.from(Map.of()).waitTimeoutValue()).isEqualTo("10s");
        assertThat(DatabricksEngineSettings.from(Map.of("wait-timeout", "PT15S"))
                .waitTimeoutValue()).isEqualTo("15s");
    }
}
