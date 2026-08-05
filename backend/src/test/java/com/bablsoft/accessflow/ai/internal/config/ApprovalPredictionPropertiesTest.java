package com.bablsoft.accessflow.ai.internal.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class ApprovalPredictionPropertiesTest {

    @Test
    void appliesAllDefaultsForNullOrOutOfRangeValues() {
        var props = new ApprovalPredictionProperties(null, null, 0, null, 0, 0, -1, 0);
        assertThat(props.enabled()).isTrue();
        assertThat(props.retrainPollInterval()).isEqualTo(Duration.ofDays(1));
        assertThat(props.minTrainingSamples()).isEqualTo(50);
        assertThat(props.trainingLookback()).isEqualTo(Duration.ofDays(180));
        assertThat(props.holdoutFraction()).isEqualTo(0.2);
        assertThat(props.minAucToServe()).isEqualTo(0.65);
        assertThat(props.l2Lambda()).isEqualTo(0.01);
        assertThat(props.maxIterations()).isEqualTo(500);
    }

    @Test
    void zeroDurationsAreDefaulted() {
        var props = new ApprovalPredictionProperties(true, Duration.ZERO, 1, Duration.ZERO, 0.5, 0.5, 0.1, 1);
        assertThat(props.retrainPollInterval()).isEqualTo(Duration.ofDays(1));
        assertThat(props.trainingLookback()).isEqualTo(Duration.ofDays(180));
    }

    @Test
    void negativeDurationsAreDefaulted() {
        var props = new ApprovalPredictionProperties(
                true, Duration.ofHours(-6), 1, Duration.ofDays(-30), 0.5, 0.5, 0.1, 1);
        assertThat(props.retrainPollInterval()).isEqualTo(Duration.ofDays(1));
        assertThat(props.trainingLookback()).isEqualTo(Duration.ofDays(180));
    }

    @Test
    void enabledFalseIsPreserved() {
        var props = new ApprovalPredictionProperties(
                false, Duration.ofDays(2), 100, Duration.ofDays(90), 0.3, 0.7, 0.05, 200);
        assertThat(props.enabled()).isFalse();
    }

    @Test
    void l2LambdaOfZeroIsPreservedNotDefaulted() {
        // Only negative l2Lambda is replaced; 0 (no regularization) is a legitimate value.
        var props = new ApprovalPredictionProperties(
                true, Duration.ofDays(2), 100, Duration.ofDays(90), 0.3, 0.7, 0.0, 200);
        assertThat(props.l2Lambda()).isZero();
    }

    @Test
    void holdoutFractionAtOrAboveOneIsDefaulted() {
        var props = new ApprovalPredictionProperties(
                true, Duration.ofDays(2), 100, Duration.ofDays(90), 1.0, 0.7, 0.05, 200);
        assertThat(props.holdoutFraction()).isEqualTo(0.2);
    }

    @Test
    void minAucToServeAboveOneIsDefaulted() {
        var props = new ApprovalPredictionProperties(
                true, Duration.ofDays(2), 100, Duration.ofDays(90), 0.3, 1.5, 0.05, 200);
        assertThat(props.minAucToServe()).isEqualTo(0.65);
    }

    @Test
    void minAucToServeOfExactlyOneIsPreserved() {
        var props = new ApprovalPredictionProperties(
                true, Duration.ofDays(2), 100, Duration.ofDays(90), 0.3, 1.0, 0.05, 200);
        assertThat(props.minAucToServe()).isEqualTo(1.0);
    }

    @Test
    void explicitValidValuesArePreserved() {
        var props = new ApprovalPredictionProperties(
                true, Duration.ofHours(12), 25, Duration.ofDays(365), 0.25, 0.8, 0.001, 1000);
        assertThat(props.enabled()).isTrue();
        assertThat(props.retrainPollInterval()).isEqualTo(Duration.ofHours(12));
        assertThat(props.minTrainingSamples()).isEqualTo(25);
        assertThat(props.trainingLookback()).isEqualTo(Duration.ofDays(365));
        assertThat(props.holdoutFraction()).isEqualTo(0.25);
        assertThat(props.minAucToServe()).isEqualTo(0.8);
        assertThat(props.l2Lambda()).isEqualTo(0.001);
        assertThat(props.maxIterations()).isEqualTo(1000);
    }
}
