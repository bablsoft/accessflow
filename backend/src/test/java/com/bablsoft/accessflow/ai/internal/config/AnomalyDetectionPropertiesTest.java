package com.bablsoft.accessflow.ai.internal.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class AnomalyDetectionPropertiesTest {

    @Test
    void appliesAllDefaultsForNullOrNonPositiveValues() {
        var props = new AnomalyDetectionProperties(null, 0, 0, 0, 0, -1, null);
        assertThat(props.lookbackWindow()).isEqualTo(Duration.ofHours(1));
        assertThat(props.zScoreThreshold()).isEqualTo(3.0);
        assertThat(props.iqrMultiplier()).isEqualTo(1.5);
        assertThat(props.minSampleSize()).isEqualTo(7);
        assertThat(props.maxBaselineSamples()).isEqualTo(90);
        assertThat(props.offHoursThreshold()).isEqualTo(0.02);
        assertThat(props.summaryEnabled()).isTrue();
    }

    @Test
    void zeroLookbackWindowDefaultsToOneHour() {
        var props = new AnomalyDetectionProperties(Duration.ZERO, 1, 1, 1, 1, 0, true);
        assertThat(props.lookbackWindow()).isEqualTo(Duration.ofHours(1));
    }

    @Test
    void negativeLookbackWindowDefaultsToOneHour() {
        var props = new AnomalyDetectionProperties(Duration.ofMinutes(-5), 1, 1, 1, 1, 0, true);
        assertThat(props.lookbackWindow()).isEqualTo(Duration.ofHours(1));
    }

    @Test
    void offHoursThresholdOfZeroIsPreservedNotDefaulted() {
        // Only negative offHoursThreshold is replaced; 0 is a legitimate value.
        var props = new AnomalyDetectionProperties(Duration.ofHours(2), 1, 1, 1, 1, 0.0, true);
        assertThat(props.offHoursThreshold()).isZero();
    }

    @Test
    void summaryEnabledFalseIsPreserved() {
        var props = new AnomalyDetectionProperties(Duration.ofHours(2), 4, 2, 5, 50, 0.05, false);
        assertThat(props.summaryEnabled()).isFalse();
    }

    @Test
    void explicitPositiveValuesArePreserved() {
        var props = new AnomalyDetectionProperties(
                Duration.ofMinutes(30), 2.5, 2.0, 10, 120, 0.05, true);
        assertThat(props.lookbackWindow()).isEqualTo(Duration.ofMinutes(30));
        assertThat(props.zScoreThreshold()).isEqualTo(2.5);
        assertThat(props.iqrMultiplier()).isEqualTo(2.0);
        assertThat(props.minSampleSize()).isEqualTo(10);
        assertThat(props.maxBaselineSamples()).isEqualTo(120);
        assertThat(props.offHoursThreshold()).isEqualTo(0.05);
        assertThat(props.summaryEnabled()).isTrue();
    }
}
