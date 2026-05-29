package com.bablsoft.accessflow.proxy.internal;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class ProxyHealthPropertiesTest {

    @Test
    void defaultsToThirtySecondsWhenTtlUnset() {
        assertThat(new ProxyHealthProperties(null).cacheTtl()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void keepsProvidedTtl() {
        assertThat(new ProxyHealthProperties(Duration.ofSeconds(5)).cacheTtl())
                .isEqualTo(Duration.ofSeconds(5));
    }
}
