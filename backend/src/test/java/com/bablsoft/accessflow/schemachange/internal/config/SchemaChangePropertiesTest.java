package com.bablsoft.accessflow.schemachange.internal.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaChangePropertiesTest {

    /** All-defaults, the shape every collaborator's test uses. */
    private static SchemaChangeProperties defaults() {
        return new SchemaChangeProperties(null, null, null, null, null, null);
    }

    @Test
    void nullFallsBackToTheDefault() {
        assertThat(defaults().maxStatements())
                .isEqualTo(SchemaChangeProperties.DEFAULT_MAX_STATEMENTS).isEqualTo(50);
    }

    @Test
    void zeroAndNegativeFallBackToTheDefault() {
        assertThat(new SchemaChangeProperties(0, null, null, null, null, null).maxStatements()).isEqualTo(50);
        assertThat(new SchemaChangeProperties(-7, null, null, null, null, null).maxStatements()).isEqualTo(50);
    }

    @Test
    void explicitValueIsKept() {
        assertThat(new SchemaChangeProperties(120, null, null, null, null, null).maxStatements()).isEqualTo(120);
        assertThat(new SchemaChangeProperties(1, null, null, null, null, null).maxStatements()).isEqualTo(1);
    }

    @Test
    void driftDefaultsAreApplied() {
        var properties = defaults();

        assertThat(properties.driftPollInterval()).isEqualTo(Duration.ofHours(6));
        assertThat(properties.driftScanTimeBudget()).isEqualTo(Duration.ofMinutes(5));
        assertThat(properties.driftMaxTablesPerScan()).isEqualTo(500);
        assertThat(properties.driftMaxFindingsPerScan()).isEqualTo(500);
    }

    @Test
    void nonPositiveDriftDurationsAndCountsFallBackToDefaults() {
        var properties = new SchemaChangeProperties(null, Duration.ZERO, Duration.ofSeconds(-1), 0, -3, null);

        assertThat(properties.driftPollInterval()).isEqualTo(Duration.ofHours(6));
        assertThat(properties.driftScanTimeBudget()).isEqualTo(Duration.ofMinutes(5));
        assertThat(properties.driftMaxTablesPerScan()).isEqualTo(500);
        assertThat(properties.driftMaxFindingsPerScan()).isEqualTo(500);
    }

    @Test
    void explicitDriftValuesAreKept() {
        var properties = new SchemaChangeProperties(null, Duration.ofHours(12), Duration.ofMinutes(2),
                40, 60, Duration.ofHours(3));

        assertThat(properties.driftPollInterval()).isEqualTo(Duration.ofHours(12));
        assertThat(properties.driftScanTimeBudget()).isEqualTo(Duration.ofMinutes(2));
        assertThat(properties.driftMaxTablesPerScan()).isEqualTo(40);
        assertThat(properties.driftMaxFindingsPerScan()).isEqualTo(60);
        assertThat(properties.driftScanLockAtMostFor()).isEqualTo(Duration.ofHours(3));
    }

    @Test
    void scanLockIsRaisedToTheFloorWhenItWouldExpireUnderARunningScan() {
        // 2 x 30m + 10m = 70m; a lock that lapses mid-scan admits the second scanner it exists to
        // keep out.
        var properties = new SchemaChangeProperties(null, null, Duration.ofMinutes(30), null, null,
                Duration.ofMinutes(20));

        assertThat(properties.driftScanLockAtMostFor()).isEqualTo(Duration.ofMinutes(70));
    }

    @Test
    void scanLockDefaultAlreadyClearsTheFloorForTheDefaultBudget() {
        // 2 x 5m + 10m = 20m, so the 30m default stands on its own.
        assertThat(defaults().driftScanLockAtMostFor()).isEqualTo(Duration.ofMinutes(30));
    }

    @Test
    void scanLockFloorHoldsEvenForATinyBudget() {
        var properties = new SchemaChangeProperties(null, null, Duration.ofSeconds(1), null, null,
                Duration.ofSeconds(5));

        assertThat(properties.driftScanLockAtMostFor()).isEqualTo(Duration.ofMinutes(10).plusSeconds(2));
    }
}
