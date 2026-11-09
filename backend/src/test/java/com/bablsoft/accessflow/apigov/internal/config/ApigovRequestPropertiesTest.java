package com.bablsoft.accessflow.apigov.internal.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class ApigovRequestPropertiesTest {

    @Test
    void keepsPositiveValues() {
        var props = new ApigovRequestProperties(1024L, 2048L, 512L, 8192, 25, 5, Duration.ofHours(12));
        assertThat(props.maxDiffEntries()).isEqualTo(25);
        assertThat(props.maxRequestBodyBytes()).isEqualTo(1024L);
        assertThat(props.maxResponseBytes()).isEqualTo(2048L);
        assertThat(props.responsePreviewBytes()).isEqualTo(512L);
        assertThat(props.healthFailureThreshold()).isEqualTo(5);
        assertThat(props.healthHistoryRetention()).isEqualTo(Duration.ofHours(12));
    }

    @Test
    void defaultsWhenNonPositive() {
        var zeroed = new ApigovRequestProperties(0L, 0L, 0L, 0, 0, 0, Duration.ZERO);
        assertThat(zeroed.maxVariableValueBytes()).isEqualTo(8192);
        assertThat(zeroed.maxDiffEntries()).isEqualTo(500);
        assertThat(zeroed.maxRequestBodyBytes()).isEqualTo(5L * 1024 * 1024);
        assertThat(zeroed.maxResponseBytes()).isEqualTo(10L * 1024 * 1024);
        assertThat(zeroed.responsePreviewBytes()).isEqualTo(65_536L);
        assertThat(zeroed.healthFailureThreshold()).isEqualTo(3);
        assertThat(zeroed.healthHistoryRetention()).isEqualTo(Duration.ofDays(7));

        var negative = new ApigovRequestProperties(-1L, -1L, -1L, 8192, -1, -1, Duration.ofDays(-1));
        assertThat(negative.maxDiffEntries()).isEqualTo(500);
        assertThat(negative.maxRequestBodyBytes()).isEqualTo(5L * 1024 * 1024);
        assertThat(negative.maxResponseBytes()).isEqualTo(10L * 1024 * 1024);
        assertThat(negative.responsePreviewBytes()).isEqualTo(65_536L);
        assertThat(negative.healthFailureThreshold()).isEqualTo(3);
        assertThat(negative.healthHistoryRetention()).isEqualTo(Duration.ofDays(7));
    }

    @Test
    void defaultsRetentionWhenNull() {
        var props = new ApigovRequestProperties(1L, 1L, 1L, 1, 1, 1, null);
        assertThat(props.healthHistoryRetention()).isEqualTo(Duration.ofDays(7));
    }
}
