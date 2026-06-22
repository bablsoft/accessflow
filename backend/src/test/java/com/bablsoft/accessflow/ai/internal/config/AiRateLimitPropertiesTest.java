package com.bablsoft.accessflow.ai.internal.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AiRateLimitPropertiesTest {

    @Test
    void appliesDefaultsForNulls() {
        var props = new AiRateLimitProperties(null, null);
        assertThat(props.requestsPerMinute()).isEqualTo(30);
        assertThat(props.tokensPerMonth()).isEqualTo(0L);
    }

    @Test
    void explicitValuesArePreserved() {
        var props = new AiRateLimitProperties(60, 5_000_000L);
        assertThat(props.requestsPerMinute()).isEqualTo(60);
        assertThat(props.tokensPerMonth()).isEqualTo(5_000_000L);
    }

    @Test
    void nonPositiveValuesArePreservedForDisabling() {
        // 0 / negative are kept as-is; the limiter interprets <= 0 as "disabled".
        var props = new AiRateLimitProperties(0, 0L);
        assertThat(props.requestsPerMinute()).isZero();
        assertThat(props.tokensPerMonth()).isZero();
    }
}
