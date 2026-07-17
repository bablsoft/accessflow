package com.bablsoft.accessflow.notifications.internal.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class TicketingPropertiesTest {

    @Test
    void appliesDefaultWhenNull() {
        var props = new TicketingProperties(null);
        assertThat(props.signatureTolerance()).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void keepsProvidedValue() {
        var props = new TicketingProperties(Duration.ofSeconds(90));
        assertThat(props.signatureTolerance()).isEqualTo(Duration.ofSeconds(90));
    }
}
