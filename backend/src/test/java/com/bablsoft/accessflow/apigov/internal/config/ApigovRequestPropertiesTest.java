package com.bablsoft.accessflow.apigov.internal.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApigovRequestPropertiesTest {

    @Test
    void keepsPositiveValues() {
        var props = new ApigovRequestProperties(1024L, 2048L, 512L, 8192);
        assertThat(props.maxRequestBodyBytes()).isEqualTo(1024L);
        assertThat(props.maxResponseBytes()).isEqualTo(2048L);
        assertThat(props.responsePreviewBytes()).isEqualTo(512L);
    }

    @Test
    void defaultsWhenNonPositive() {
        var zeroed = new ApigovRequestProperties(0L, 0L, 0L, 8192);
        assertThat(zeroed.maxRequestBodyBytes()).isEqualTo(5L * 1024 * 1024);
        assertThat(zeroed.maxResponseBytes()).isEqualTo(10L * 1024 * 1024);
        assertThat(zeroed.responsePreviewBytes()).isEqualTo(65_536L);

        var negative = new ApigovRequestProperties(-1L, -1L, -1L, 8192);
        assertThat(negative.maxRequestBodyBytes()).isEqualTo(5L * 1024 * 1024);
        assertThat(negative.maxResponseBytes()).isEqualTo(10L * 1024 * 1024);
        assertThat(negative.responsePreviewBytes()).isEqualTo(65_536L);
    }
}
