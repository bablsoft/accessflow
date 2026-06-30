package com.bablsoft.accessflow.apigov.internal.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApigovRequestPropertiesTest {

    @Test
    void keepsPositiveValue() {
        assertThat(new ApigovRequestProperties(1024L).maxRequestBodyBytes()).isEqualTo(1024L);
    }

    @Test
    void defaultsWhenNonPositive() {
        assertThat(new ApigovRequestProperties(0L).maxRequestBodyBytes()).isEqualTo(5L * 1024 * 1024);
        assertThat(new ApigovRequestProperties(-1L).maxRequestBodyBytes()).isEqualTo(5L * 1024 * 1024);
    }
}
