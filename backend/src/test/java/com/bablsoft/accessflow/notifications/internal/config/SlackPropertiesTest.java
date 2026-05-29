package com.bablsoft.accessflow.notifications.internal.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class SlackPropertiesTest {

    @Test
    void appliesDefaultsWhenNull() {
        var props = new SlackProperties(null, null);
        assertThat(props.linkCodeTtl()).isEqualTo(Duration.ofMinutes(10));
        assertThat(props.signatureTolerance()).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void keepsProvidedValues() {
        var props = new SlackProperties(Duration.ofMinutes(2), Duration.ofSeconds(90));
        assertThat(props.linkCodeTtl()).isEqualTo(Duration.ofMinutes(2));
        assertThat(props.signatureTolerance()).isEqualTo(Duration.ofSeconds(90));
    }
}
