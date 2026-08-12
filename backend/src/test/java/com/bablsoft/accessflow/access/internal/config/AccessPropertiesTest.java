package com.bablsoft.accessflow.access.internal.config;

import com.bablsoft.accessflow.access.internal.config.AccessProperties.Usage;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class AccessPropertiesTest {

    private static Usage usage(Duration aggregation, Duration backfill, Duration staleness,
                               Duration minObservation, double overScoped, int maxRowsPerTick,
                               int maxTrackedTargets, int maxReportRows, Boolean nudgeEnabled,
                               Duration nudgeCooldown) {
        return new Usage(aggregation, backfill, staleness, minObservation, overScoped,
                maxRowsPerTick, 0, maxTrackedTargets, maxReportRows, nudgeEnabled, nudgeCooldown);
    }

    @Test
    void appliesDefaultsWhenAllNull() {
        var props = new AccessProperties(null, null, null, null);
        assertThat(props.grantExpiryPollInterval()).isEqualTo(Duration.ofMinutes(5));
        assertThat(props.minDuration()).isEqualTo(Duration.ofMinutes(15));
        assertThat(props.maxDuration()).isEqualTo(Duration.ofDays(30));
        assertThat(props.usage()).isNotNull();
    }

    @Test
    void keepsExplicitValues() {
        var props = new AccessProperties(Duration.ofMinutes(1), Duration.ofMinutes(2),
                Duration.ofHours(3), null);
        assertThat(props.grantExpiryPollInterval()).isEqualTo(Duration.ofMinutes(1));
        assertThat(props.minDuration()).isEqualTo(Duration.ofMinutes(2));
        assertThat(props.maxDuration()).isEqualTo(Duration.ofHours(3));
    }

    @Test
    void appliesDefaultsIndividually() {
        var props = new AccessProperties(null, Duration.ofMinutes(30), null, null);
        assertThat(props.grantExpiryPollInterval()).isEqualTo(Duration.ofMinutes(5));
        assertThat(props.minDuration()).isEqualTo(Duration.ofMinutes(30));
        assertThat(props.maxDuration()).isEqualTo(Duration.ofDays(30));
    }

    @Test
    void usageAppliesDefaultsWhenUnset() {
        var props = new AccessProperties(null, null, null, null);
        var usage = props.usage();
        assertThat(usage.aggregationPollInterval()).isEqualTo(Duration.ofHours(1));
        assertThat(usage.backfillWindow()).isEqualTo(Duration.ofDays(90));
        assertThat(usage.stalenessThreshold()).isEqualTo(Duration.ofDays(60));
        assertThat(usage.minObservationWindow()).isEqualTo(Duration.ofDays(14));
        assertThat(usage.overScopedThreshold()).isEqualTo(0.5);
        assertThat(usage.maxRowsPerTick()).isEqualTo(50_000);
        assertThat(usage.maxPagesPerTick()).isEqualTo(20);
        assertThat(usage.maxTrackedTargets()).isEqualTo(200);
        assertThat(usage.maxReportRows()).isEqualTo(50_000);
        assertThat(usage.nudgeEnabled()).isTrue();
        assertThat(usage.nudgeCooldown()).isEqualTo(Duration.ofDays(30));
    }

    @Test
    void usageKeepsExplicitValues() {
        var usage = usage(Duration.ofMinutes(30), Duration.ofDays(7), Duration.ofDays(10),
                Duration.ofDays(2), 0.25, 100, 10, 500, Boolean.FALSE, Duration.ofDays(1));
        assertThat(usage.aggregationPollInterval()).isEqualTo(Duration.ofMinutes(30));
        assertThat(usage.backfillWindow()).isEqualTo(Duration.ofDays(7));
        assertThat(usage.stalenessThreshold()).isEqualTo(Duration.ofDays(10));
        assertThat(usage.minObservationWindow()).isEqualTo(Duration.ofDays(2));
        assertThat(usage.overScopedThreshold()).isEqualTo(0.25);
        assertThat(usage.maxRowsPerTick()).isEqualTo(100);
        assertThat(usage.maxTrackedTargets()).isEqualTo(10);
        assertThat(usage.maxReportRows()).isEqualTo(500);
        assertThat(usage.nudgeEnabled()).isFalse();
        assertThat(usage.nudgeCooldown()).isEqualTo(Duration.ofDays(1));
    }

    /** A ratio outside (0, 1] is meaningless as a fraction and must fall back, not be clamped in. */
    @Test
    void usageRejectsOutOfRangeOverScopedThreshold() {
        assertThat(usage(null, null, null, null, 0, 0, 0, 0, null, null).overScopedThreshold())
                .isEqualTo(0.5);
        assertThat(usage(null, null, null, null, -1, 0, 0, 0, null, null).overScopedThreshold())
                .isEqualTo(0.5);
        assertThat(usage(null, null, null, null, 1.5, 0, 0, 0, null, null).overScopedThreshold())
                .isEqualTo(0.5);
        assertThat(usage(null, null, null, null, 1.0, 0, 0, 0, null, null).overScopedThreshold())
                .isEqualTo(1.0);
    }

    /** Zero is a legitimate min-observation window ("judge immediately"); negative is not. */
    @Test
    void usageAllowsZeroMinObservationWindowButNotNegative() {
        assertThat(usage(null, null, null, Duration.ZERO, 0, 0, 0, 0, null, null)
                .minObservationWindow()).isEqualTo(Duration.ZERO);
        assertThat(usage(null, null, null, Duration.ofDays(-1), 0, 0, 0, 0, null, null)
                .minObservationWindow()).isEqualTo(Duration.ofDays(14));
    }
}
