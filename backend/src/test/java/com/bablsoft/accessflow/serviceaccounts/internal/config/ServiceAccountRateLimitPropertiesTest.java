package com.bablsoft.accessflow.serviceaccounts.internal.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ServiceAccountRateLimitPropertiesTest {

    @Test
    void nullValuesFallBackToTheDefaults() {
        var props = new ServiceAccountRateLimitProperties(null, null);
        assertThat(props.requestsPerMinute()).isEqualTo(120);
        assertThat(props.requestsPerDay()).isZero();
    }

    @Test
    void explicitValuesAreKeptIncludingDisablingZero() {
        var props = new ServiceAccountRateLimitProperties(0, 5000);
        assertThat(props.requestsPerMinute()).isZero();
        assertThat(props.requestsPerDay()).isEqualTo(5000);
    }
}
