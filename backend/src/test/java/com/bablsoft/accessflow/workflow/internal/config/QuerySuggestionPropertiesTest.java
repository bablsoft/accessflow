package com.bablsoft.accessflow.workflow.internal.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class QuerySuggestionPropertiesTest {

    private QuerySuggestionProperties allDefaults() {
        return new QuerySuggestionProperties(null, null, null, 0, 0, 0, 0, 0, null, -1, -1, -1, 0,
                0, null);
    }

    @Test
    void nullAndNonPositiveValuesFallBackToDefaults() {
        var props = allDefaults();
        assertThat(props.enabled()).isTrue();
        assertThat(props.aggregationPollInterval()).isEqualTo(Duration.ofHours(6));
        assertThat(props.lookback()).isEqualTo(Duration.ofDays(90));
        assertThat(props.maxCorpusRowsPerDatasource()).isEqualTo(2000);
        assertThat(props.maxSuggestionsPerDatasource()).isEqualTo(50);
        assertThat(props.maxTrackedSubmitters()).isEqualTo(50);
        assertThat(props.minApprovedCount()).isEqualTo(2);
        assertThat(props.maxSqlLength()).isEqualTo(4000);
        assertThat(props.recencyHalfLife()).isEqualTo(Duration.ofDays(14));
        assertThat(props.weightFrequency()).isEqualTo(1.0);
        assertThat(props.weightRecency()).isEqualTo(1.0);
        assertThat(props.weightTableOverlap()).isEqualTo(0.5);
        assertThat(props.defaultLimit()).isEqualTo(10);
        assertThat(props.maxLimit()).isEqualTo(50);
        assertThat(props.recomputeLockAtMostFor()).isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    void zeroAndNegativeDurationsAreTreatedAsUnset() {
        var zeroed = new QuerySuggestionProperties(false, Duration.ZERO, Duration.ofDays(-1), 0, 0,
                0, 0, 0, Duration.ZERO, 0, 0, 0, 0, 0, Duration.ofSeconds(-1));
        assertThat(zeroed.enabled()).isFalse();
        assertThat(zeroed.aggregationPollInterval()).isEqualTo(Duration.ofHours(6));
        assertThat(zeroed.lookback()).isEqualTo(Duration.ofDays(90));
        assertThat(zeroed.recencyHalfLife()).isEqualTo(Duration.ofDays(14));
        assertThat(zeroed.recomputeLockAtMostFor()).isEqualTo(Duration.ofMinutes(10));
        // Zero is a legitimate weight — "switch this term off" — so it must survive.
        assertThat(zeroed.weightFrequency()).isZero();
        assertThat(zeroed.weightRecency()).isZero();
        assertThat(zeroed.weightTableOverlap()).isZero();
    }

    @Test
    void explicitValuesAreKept() {
        var props = new QuerySuggestionProperties(true, Duration.ofHours(1), Duration.ofDays(30),
                10, 5, 3, 4, 200, Duration.ofDays(7), 2.0, 3.0, 4.0, 7, 20, Duration.ofMinutes(2));
        assertThat(props.aggregationPollInterval()).isEqualTo(Duration.ofHours(1));
        assertThat(props.lookback()).isEqualTo(Duration.ofDays(30));
        assertThat(props.maxCorpusRowsPerDatasource()).isEqualTo(10);
        assertThat(props.maxSuggestionsPerDatasource()).isEqualTo(5);
        assertThat(props.maxTrackedSubmitters()).isEqualTo(3);
        assertThat(props.minApprovedCount()).isEqualTo(4);
        assertThat(props.maxSqlLength()).isEqualTo(200);
        assertThat(props.recencyHalfLife()).isEqualTo(Duration.ofDays(7));
        assertThat(props.defaultLimit()).isEqualTo(7);
        assertThat(props.maxLimit()).isEqualTo(20);
        assertThat(props.recomputeLockAtMostFor()).isEqualTo(Duration.ofMinutes(2));
    }

    @Test
    void defaultLimitIsClampedToMaxLimit() {
        var props = new QuerySuggestionProperties(true, null, null, 0, 0, 0, 0, 0, null, 1, 1, 1,
                90, 20, null);
        assertThat(props.defaultLimit()).isEqualTo(20);
    }
}
