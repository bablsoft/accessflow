package com.bablsoft.accessflow.deploygov.internal.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DeploygovPropertiesTest {

    @Test
    void bindsAllThreeKeys() {
        var source = new MapConfigurationPropertySource(Map.of(
                "accessflow.deploygov.timeout-check", "PT2M",
                "accessflow.deploygov.release-check", "PT30S",
                "accessflow.deploygov.escalation-poll-interval", "PT10M"));

        var properties = new Binder(source)
                .bind("accessflow.deploygov", DeploygovProperties.class).get();

        assertThat(properties.timeoutCheck()).isEqualTo(Duration.ofMinutes(2));
        assertThat(properties.releaseCheck()).isEqualTo(Duration.ofSeconds(30));
        assertThat(properties.escalationPollInterval()).isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    void appliesDefaultsWhenUnset() {
        var source = new MapConfigurationPropertySource(Map.of(
                "accessflow.deploygov.release-check", "PT45S"));

        var properties = new Binder(source)
                .bind("accessflow.deploygov", DeploygovProperties.class).get();

        assertThat(properties.timeoutCheck()).isEqualTo(Duration.ofMinutes(5));
        assertThat(properties.releaseCheck()).isEqualTo(Duration.ofSeconds(45));
        assertThat(properties.escalationPollInterval()).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void compactConstructorDefaultsEveryNull() {
        var properties = new DeploygovProperties(null, null, null);

        assertThat(properties.timeoutCheck()).isEqualTo(Duration.ofMinutes(5));
        assertThat(properties.releaseCheck()).isEqualTo(Duration.ofMinutes(1));
        assertThat(properties.escalationPollInterval()).isEqualTo(Duration.ofMinutes(5));
    }
}
