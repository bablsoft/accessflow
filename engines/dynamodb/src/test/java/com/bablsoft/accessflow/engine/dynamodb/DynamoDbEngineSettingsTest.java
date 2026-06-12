package com.bablsoft.accessflow.engine.dynamodb;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DynamoDbEngineSettingsTest {

    @Test
    void parsesIso8601Durations() {
        var settings = DynamoDbEngineSettings.from(
                Map.of("connect-timeout", "PT3S", "api-call-timeout", "PT45S"));
        assertThat(settings.connectTimeout()).isEqualTo(Duration.ofSeconds(3));
        assertThat(settings.apiCallTimeout()).isEqualTo(Duration.ofSeconds(45));
    }

    @Test
    void fallsBackToDefaultsForMissingNullOrEmptyConfig() {
        assertThat(DynamoDbEngineSettings.from(null).connectTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(DynamoDbEngineSettings.from(Map.of()).apiCallTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(DynamoDbEngineSettings.from(Map.of("connect-timeout", "")).connectTimeout())
                .isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void fallsBackToDefaultsForUnparseableNegativeOrZero() {
        assertThat(DynamoDbEngineSettings.from(Map.of("connect-timeout", "garbage")).connectTimeout())
                .isEqualTo(Duration.ofSeconds(10));
        assertThat(DynamoDbEngineSettings.from(Map.of("api-call-timeout", "PT0S")).apiCallTimeout())
                .isEqualTo(Duration.ofSeconds(30));
        assertThat(DynamoDbEngineSettings.from(Map.of("connect-timeout", "PT-5S")).connectTimeout())
                .isEqualTo(Duration.ofSeconds(10));
    }
}
