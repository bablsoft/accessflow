package com.bablsoft.accessflow.workflow.internal.hook;

import com.bablsoft.accessflow.workflow.internal.config.DecisionHookProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class DecisionHookPropertiesTest {

    @Test
    void defaultsAreSafe() {
        var properties = new DecisionHookProperties(null, null, null);

        assertThat(properties.allowPrivateNetwork()).isFalse();
        assertThat(properties.circuitFailureThreshold()).isEqualTo(5);
        assertThat(properties.circuitOpenDuration()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void unusableValuesFallBackToTheDefaults() {
        var properties = new DecisionHookProperties(true, 0, Duration.ZERO);

        assertThat(properties.allowPrivateNetwork()).isTrue();
        assertThat(properties.circuitFailureThreshold()).isEqualTo(5);
        assertThat(properties.circuitOpenDuration()).isEqualTo(Duration.ofSeconds(30));
        assertThat(new DecisionHookProperties(false, 2, Duration.ofSeconds(-1))
                .circuitOpenDuration()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void explicitValuesAreKept() {
        var properties = new DecisionHookProperties(false, 2, Duration.ofMinutes(1));

        assertThat(properties.circuitFailureThreshold()).isEqualTo(2);
        assertThat(properties.circuitOpenDuration()).isEqualTo(Duration.ofMinutes(1));
    }
}
