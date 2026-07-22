package com.bablsoft.accessflow.engine.snowflake;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SnowflakeEngineSettingsTest {

    @Test
    void defaultsWhenConfigIsNullOrEmpty() {
        for (var settings : new SnowflakeEngineSettings[]{
                SnowflakeEngineSettings.from(null),
                SnowflakeEngineSettings.from(Map.of())}) {
            assertThat(settings.loginTimeout()).isEqualTo(Duration.ofSeconds(30));
            assertThat(settings.networkTimeout()).isEqualTo(Duration.ofSeconds(60));
        }
    }

    @Test
    void parsesIsoDurations() {
        var settings = SnowflakeEngineSettings.from(Map.of(
                "login-timeout", "PT5S",
                "network-timeout", "PT2M"));
        assertThat(settings.loginTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(settings.networkTimeout()).isEqualTo(Duration.ofMinutes(2));
    }

    @Test
    void fallsBackOnUnparseableOrNonPositiveValues() {
        var garbage = SnowflakeEngineSettings.from(Map.of(
                "login-timeout", "ten seconds",
                "network-timeout", ""));
        assertThat(garbage.loginTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(garbage.networkTimeout()).isEqualTo(Duration.ofSeconds(60));

        var nonPositive = SnowflakeEngineSettings.from(Map.of(
                "login-timeout", "PT0S",
                "network-timeout", "PT-5S"));
        assertThat(nonPositive.loginTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(nonPositive.networkTimeout()).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void ignoresUnknownKeys() {
        var settings = SnowflakeEngineSettings.from(Map.of("something-else", "PT1S"));
        assertThat(settings.loginTimeout()).isEqualTo(Duration.ofSeconds(30));
    }
}
