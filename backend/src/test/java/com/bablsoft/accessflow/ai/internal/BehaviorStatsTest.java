package com.bablsoft.accessflow.ai.internal;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class BehaviorStatsTest {

    @Test
    void meanOfEmptyIsZero() {
        assertThat(BehaviorStats.mean(List.of())).isZero();
    }

    @Test
    void meanAveragesValues() {
        assertThat(BehaviorStats.mean(List.of(1.0, 2.0, 3.0, 4.0))).isEqualTo(2.5);
    }

    @Test
    void sampleStddevIsZeroForFewerThanTwoValues() {
        assertThat(BehaviorStats.sampleStddev(List.of())).isZero();
        assertThat(BehaviorStats.sampleStddev(List.of(5.0))).isZero();
    }

    @Test
    void sampleStddevUsesNMinusOneDenominator() {
        // values {2,4,4,4,5,5,7,9}: sample stddev = sqrt(32/7) ~= 2.138
        var values = List.of(2.0, 4.0, 4.0, 4.0, 5.0, 5.0, 7.0, 9.0);
        assertThat(BehaviorStats.sampleStddev(values)).isCloseTo(2.13809, within(1e-4));
    }

    @Test
    void sampleStddevIsZeroForConstantValues() {
        assertThat(BehaviorStats.sampleStddev(List.of(3.0, 3.0, 3.0))).isZero();
    }

    @Test
    void percentileOfEmptyIsZero() {
        assertThat(BehaviorStats.percentile(List.of(), 0.5)).isZero();
    }

    @Test
    void percentileOfSingleValueReturnsThatValue() {
        assertThat(BehaviorStats.percentile(List.of(42.0), 0.25)).isEqualTo(42.0);
        assertThat(BehaviorStats.percentile(List.of(42.0), 0.99)).isEqualTo(42.0);
    }

    @Test
    void percentileInterpolatesBetweenRanks() {
        // sorted {10,20,30,40}; p=0.5 -> rank 1.5 -> between 20 and 30 -> 25
        var values = List.of(40.0, 10.0, 30.0, 20.0);
        assertThat(BehaviorStats.percentile(values, 0.5)).isEqualTo(25.0);
    }

    @Test
    void percentileHitsExactRankWithoutInterpolation() {
        // sorted {10,20,30}; p=0.5 -> rank 1.0 -> exactly index 1 -> 20
        var values = List.of(10.0, 20.0, 30.0);
        assertThat(BehaviorStats.percentile(values, 0.5)).isEqualTo(20.0);
    }

    @Test
    void percentileBoundsReturnMinAndMax() {
        var values = List.of(10.0, 20.0, 30.0, 40.0);
        assertThat(BehaviorStats.percentile(values, 0.0)).isEqualTo(10.0);
        assertThat(BehaviorStats.percentile(values, 1.0)).isEqualTo(40.0);
    }
}
