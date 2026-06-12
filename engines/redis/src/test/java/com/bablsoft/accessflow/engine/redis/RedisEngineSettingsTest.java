package com.bablsoft.accessflow.engine.redis;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RedisEngineSettingsTest {

    @Test
    void emptyConfigYieldsDefaults() {
        var settings = RedisEngineSettings.from(Map.of());
        assertThat(settings.connectTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(settings.socketTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(settings.maxPoolSize()).isEqualTo(10);
    }

    @Test
    void nullConfigIsTreatedAsEmpty() {
        assertThat(RedisEngineSettings.from(null).maxPoolSize()).isEqualTo(10);
    }

    @Test
    void parsesProvidedValues() {
        var settings = RedisEngineSettings.from(Map.of(
                "connect-timeout", "PT1S",
                "socket-timeout", "PT2S",
                "max-pool-size", "20"));
        assertThat(settings.connectTimeoutMillis()).isEqualTo(1000);
        assertThat(settings.socketTimeoutMillis()).isEqualTo(2000);
        assertThat(settings.maxPoolSize()).isEqualTo(20);
    }

    @Test
    void fallsBackOnUnparseableValues() {
        var settings = RedisEngineSettings.from(Map.of(
                "connect-timeout", "garbage",
                "max-pool-size", "notanumber"));
        assertThat(settings.connectTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(settings.maxPoolSize()).isEqualTo(10);
    }
}
