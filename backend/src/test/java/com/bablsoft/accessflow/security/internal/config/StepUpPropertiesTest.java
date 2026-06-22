package com.bablsoft.accessflow.security.internal.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class StepUpPropertiesTest {

    @Test
    void defaultsTtlWhenNull() {
        assertThat(new StepUpProperties(null).ttl()).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void keepsExplicitTtl() {
        assertThat(new StepUpProperties(Duration.ofMinutes(2)).ttl()).isEqualTo(Duration.ofMinutes(2));
    }
}
