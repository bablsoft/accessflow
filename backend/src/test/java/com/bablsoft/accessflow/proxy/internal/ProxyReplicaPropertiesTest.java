package com.bablsoft.accessflow.proxy.internal;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class ProxyReplicaPropertiesTest {

    @Test
    void appliesDefaultsWhenUnset() {
        var props = new ProxyReplicaProperties(null, null, null);
        assertThat(props.probeInterval()).isEqualTo(Duration.ofSeconds(30));
        assertThat(props.probeTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(props.cooldown()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void keepsProvidedValues() {
        var props = new ProxyReplicaProperties(
                Duration.ofMinutes(1), Duration.ofSeconds(2), Duration.ofSeconds(10));
        assertThat(props.probeInterval()).isEqualTo(Duration.ofMinutes(1));
        assertThat(props.probeTimeout()).isEqualTo(Duration.ofSeconds(2));
        assertThat(props.cooldown()).isEqualTo(Duration.ofSeconds(10));
    }
}
