package com.bablsoft.accessflow.access.internal.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class AccessPropertiesTest {

    @Test
    void appliesDefaultsWhenAllNull() {
        var props = new AccessProperties(null, null, null);
        assertThat(props.grantExpiryPollInterval()).isEqualTo(Duration.ofMinutes(5));
        assertThat(props.minDuration()).isEqualTo(Duration.ofMinutes(15));
        assertThat(props.maxDuration()).isEqualTo(Duration.ofDays(30));
    }

    @Test
    void keepsExplicitValues() {
        var props = new AccessProperties(Duration.ofMinutes(1), Duration.ofMinutes(2),
                Duration.ofHours(3));
        assertThat(props.grantExpiryPollInterval()).isEqualTo(Duration.ofMinutes(1));
        assertThat(props.minDuration()).isEqualTo(Duration.ofMinutes(2));
        assertThat(props.maxDuration()).isEqualTo(Duration.ofHours(3));
    }

    @Test
    void appliesDefaultsIndividually() {
        var props = new AccessProperties(null, Duration.ofMinutes(30), null);
        assertThat(props.grantExpiryPollInterval()).isEqualTo(Duration.ofMinutes(5));
        assertThat(props.minDuration()).isEqualTo(Duration.ofMinutes(30));
        assertThat(props.maxDuration()).isEqualTo(Duration.ofDays(30));
    }
}
