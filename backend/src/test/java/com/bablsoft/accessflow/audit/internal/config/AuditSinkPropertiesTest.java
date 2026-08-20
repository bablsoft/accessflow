package com.bablsoft.accessflow.audit.internal.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class AuditSinkPropertiesTest {

    @Test
    void nullsFallBackToDefaults() {
        var properties = new AuditSinkProperties(null, null, null);

        assertThat(properties.drainInterval()).isEqualTo(Duration.ofSeconds(30));
        assertThat(properties.batchSize()).isEqualTo(500);
        assertThat(properties.maxBatchesPerTick()).isEqualTo(5);
    }

    @Test
    void nonPositiveValuesFallBackToDefaults() {
        var properties = new AuditSinkProperties(Duration.ZERO, 0, -1);

        assertThat(properties.drainInterval()).isEqualTo(Duration.ofSeconds(30));
        assertThat(properties.batchSize()).isEqualTo(500);
        assertThat(properties.maxBatchesPerTick()).isEqualTo(5);
    }

    @Test
    void negativeDrainIntervalFallsBackToDefault() {
        var properties = new AuditSinkProperties(Duration.ofSeconds(-5), null, null);

        assertThat(properties.drainInterval()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void explicitValuesAreHonored() {
        var properties = new AuditSinkProperties(Duration.ofMinutes(1), 100, 2);

        assertThat(properties.drainInterval()).isEqualTo(Duration.ofMinutes(1));
        assertThat(properties.batchSize()).isEqualTo(100);
        assertThat(properties.maxBatchesPerTick()).isEqualTo(2);
    }
}
