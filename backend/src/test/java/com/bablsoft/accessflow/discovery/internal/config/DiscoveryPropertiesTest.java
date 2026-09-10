package com.bablsoft.accessflow.discovery.internal.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class DiscoveryPropertiesTest {

    @Test
    void nullFieldsFallBackToDefaults() {
        var properties = new DiscoveryProperties(null, null, null, null, null, null, null,
                null, null);

        assertThat(properties.scanPollInterval()).isEqualTo(Duration.ofMinutes(15));
        assertThat(properties.scanTimeBudget()).isEqualTo(Duration.ofMinutes(10));
        assertThat(properties.sampleStatementTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(properties.maxTablesPerScan()).isEqualTo(200);
        assertThat(properties.maxAiTablesPerScan()).isEqualTo(25);
        assertThat(properties.maxNestedDepth()).isEqualTo(5);
        assertThat(properties.maxNestedLeavesPerRow()).isEqualTo(100);
        assertThat(properties.staleScansBeforeExpiry()).isEqualTo(3);
        assertThat(properties.scanLockAtMostFor()).isEqualTo(Duration.ofMinutes(30));
    }

    @Test
    void nonPositiveDurationsAndCountsFallBackToDefaults() {
        var properties = new DiscoveryProperties(Duration.ZERO, Duration.ZERO,
                Duration.ofSeconds(-1), 0, -1, 0, -1, 0, null);

        assertThat(properties.scanTimeBudget()).isEqualTo(Duration.ofMinutes(10));
        assertThat(properties.sampleStatementTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(properties.maxTablesPerScan()).isEqualTo(200);
        assertThat(properties.maxAiTablesPerScan()).isEqualTo(25);
        assertThat(properties.maxNestedDepth()).isEqualTo(5);
        assertThat(properties.maxNestedLeavesPerRow()).isEqualTo(100);
        assertThat(properties.staleScansBeforeExpiry()).isEqualTo(3);
    }

    @Test
    void explicitValuesAreKept() {
        var properties = new DiscoveryProperties(Duration.ofMinutes(5), Duration.ofMinutes(2),
                Duration.ofSeconds(3), 50, 0, 3, 20, 5, Duration.ofMinutes(45));

        assertThat(properties.scanPollInterval()).isEqualTo(Duration.ofMinutes(5));
        assertThat(properties.scanTimeBudget()).isEqualTo(Duration.ofMinutes(2));
        assertThat(properties.sampleStatementTimeout()).isEqualTo(Duration.ofSeconds(3));
        assertThat(properties.maxTablesPerScan()).isEqualTo(50);
        assertThat(properties.maxAiTablesPerScan()).isZero();
        assertThat(properties.maxNestedDepth()).isEqualTo(3);
        assertThat(properties.maxNestedLeavesPerRow()).isEqualTo(20);
        assertThat(properties.staleScansBeforeExpiry()).isEqualTo(5);
        assertThat(properties.scanLockAtMostFor()).isEqualTo(Duration.ofMinutes(45));
    }

    @Test
    void scanLockIsRaisedToTheFloorWhenItWouldExpireUnderARunningScan() {
        // A lock that lapses while the scan is still going lets a second replica start one —
        // exactly the race the lock exists to prevent (AF-660) — so it is raised, not honoured.
        var tooShort = new DiscoveryProperties(null, Duration.ofMinutes(10), null, null, null,
                null, null, null, Duration.ofMinutes(5));
        assertThat(tooShort.scanLockAtMostFor()).isEqualTo(Duration.ofMinutes(30));

        // The PT30M default is below the floor once the budget is raised, and follows it up.
        var longBudget = new DiscoveryProperties(null, Duration.ofHours(4), null, null, null,
                null, null, null, null);
        assertThat(longBudget.scanLockAtMostFor()).isEqualTo(Duration.ofHours(8).plusMinutes(10));
    }

    @Test
    void scanLockFloorHoldsEvenForATinyScanTimeBudget() {
        // The additive term is the point: a bare 2x multiple would let a PT30S budget shrink the
        // lock to PT1M, which a single 30-second AI call outlives.
        var tinyBudget = new DiscoveryProperties(null, Duration.ofSeconds(30), null, null, null,
                null, null, null, Duration.ofMinutes(1));

        assertThat(tinyBudget.scanLockAtMostFor()).isEqualTo(Duration.ofMinutes(11));
    }

    @Test
    void nonPositiveScanLockFallsBackToTheFloor() {
        var negative = new DiscoveryProperties(null, null, null, null, null, null, null, null,
                Duration.ofSeconds(-1));

        assertThat(negative.scanLockAtMostFor()).isEqualTo(Duration.ofMinutes(30));
    }
}
