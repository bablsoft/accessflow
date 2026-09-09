package com.bablsoft.accessflow.core.internal.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** Defaulting rules for the policy-simulator caps (issue AF-630). */
class PolicySimulationPropertiesTest {

    @Test
    void keepsExplicitValues() {
        var properties = new PolicySimulationProperties(2_000, 50, 25, Duration.ofDays(30));

        assertThat(properties.maxRows()).isEqualTo(2_000);
        assertThat(properties.maxSamples()).isEqualTo(50);
        assertThat(properties.maxUserImpacts()).isEqualTo(25);
        assertThat(properties.maxWindow()).isEqualTo(Duration.ofDays(30));
    }

    @Test
    void defaultsEveryUnsetNumericCap() {
        var properties = new PolicySimulationProperties(0, 0, 0, null);

        // Far below the compliance report's 50 000: each replayed row is re-parsed and evaluated
        // twice, once per arm of the A/B.
        assertThat(properties.maxRows()).isEqualTo(5_000);
        assertThat(properties.maxSamples()).isEqualTo(100);
        assertThat(properties.maxUserImpacts()).isEqualTo(100);
        assertThat(properties.maxWindow()).isEqualTo(Duration.ofDays(90));
    }

    @Test
    void defaultsNegativeCaps() {
        var properties = new PolicySimulationProperties(-1, -1, -1, Duration.ofDays(-1));

        assertThat(properties.maxRows()).isEqualTo(5_000);
        assertThat(properties.maxSamples()).isEqualTo(100);
        assertThat(properties.maxUserImpacts()).isEqualTo(100);
        assertThat(properties.maxWindow()).isEqualTo(Duration.ofDays(90));
    }

    @Test
    void defaultsAZeroWindow() {
        assertThat(new PolicySimulationProperties(1, 1, 1, Duration.ZERO).maxWindow())
                .isEqualTo(Duration.ofDays(90));
    }

    @Test
    void defaultsEachCapIndependently() {
        var properties = new PolicySimulationProperties(1_000, 0, 10, Duration.ofDays(7));

        assertThat(properties.maxRows()).isEqualTo(1_000);
        assertThat(properties.maxSamples()).isEqualTo(100);
        assertThat(properties.maxUserImpacts()).isEqualTo(10);
        assertThat(properties.maxWindow()).isEqualTo(Duration.ofDays(7));
    }
}
