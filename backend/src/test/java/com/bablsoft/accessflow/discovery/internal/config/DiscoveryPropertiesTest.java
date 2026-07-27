package com.bablsoft.accessflow.discovery.internal.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class DiscoveryPropertiesTest {

    @Test
    void nullFieldsFallBackToDefaults() {
        var properties = new DiscoveryProperties(null, null, null, null, null);

        assertThat(properties.scanPollInterval()).isEqualTo(Duration.ofMinutes(15));
        assertThat(properties.scanTimeBudget()).isEqualTo(Duration.ofMinutes(10));
        assertThat(properties.sampleStatementTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(properties.maxTablesPerScan()).isEqualTo(200);
        assertThat(properties.maxAiTablesPerScan()).isEqualTo(25);
    }

    @Test
    void nonPositiveDurationsAndCountsFallBackToDefaults() {
        var properties = new DiscoveryProperties(Duration.ZERO, Duration.ZERO,
                Duration.ofSeconds(-1), 0, -1);

        assertThat(properties.scanTimeBudget()).isEqualTo(Duration.ofMinutes(10));
        assertThat(properties.sampleStatementTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(properties.maxTablesPerScan()).isEqualTo(200);
        assertThat(properties.maxAiTablesPerScan()).isEqualTo(25);
    }

    @Test
    void explicitValuesAreKept() {
        var properties = new DiscoveryProperties(Duration.ofMinutes(5), Duration.ofMinutes(2),
                Duration.ofSeconds(3), 50, 0);

        assertThat(properties.scanPollInterval()).isEqualTo(Duration.ofMinutes(5));
        assertThat(properties.scanTimeBudget()).isEqualTo(Duration.ofMinutes(2));
        assertThat(properties.sampleStatementTimeout()).isEqualTo(Duration.ofSeconds(3));
        assertThat(properties.maxTablesPerScan()).isEqualTo(50);
        assertThat(properties.maxAiTablesPerScan()).isZero();
    }
}
