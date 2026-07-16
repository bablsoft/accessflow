package com.bablsoft.accessflow.proxy.internal;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class ProxyCachePropertiesTest {

    @Test
    void appliesDefaultsWhenUnset() {
        var props = new ProxyCacheProperties(null, null, null);
        assertThat(props.enabled()).isTrue();
        assertThat(props.defaultTtl()).isEqualTo(Duration.ofSeconds(60));
        assertThat(props.maxEntryBytes()).isEqualTo(1_000_000L);
    }

    @Test
    void keepsProvidedValues() {
        var props = new ProxyCacheProperties(false, Duration.ofMinutes(5), 42L);
        assertThat(props.enabled()).isFalse();
        assertThat(props.defaultTtl()).isEqualTo(Duration.ofMinutes(5));
        assertThat(props.maxEntryBytes()).isEqualTo(42L);
    }
}
