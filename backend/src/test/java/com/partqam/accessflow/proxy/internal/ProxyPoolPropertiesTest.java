package com.partqam.accessflow.proxy.internal;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class ProxyPoolPropertiesTest {

    @Test
    void allNullArgumentsYieldDocumentedDefaults() {
        var props = new ProxyPoolProperties(null, null, null, null, null);

        assertThat(props.connectionTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(props.idleTimeout()).isEqualTo(Duration.ofMinutes(10));
        assertThat(props.maxLifetime()).isEqualTo(Duration.ofMinutes(30));
        assertThat(props.leakDetectionThreshold()).isEqualTo(Duration.ZERO);
        assertThat(props.poolNamePrefix()).isEqualTo("accessflow-ds-");
    }

    @Test
    void explicitValuesPassThroughUnchanged() {
        var props = new ProxyPoolProperties(
                Duration.ofSeconds(5), Duration.ofSeconds(60), Duration.ofMinutes(15),
                Duration.ofSeconds(2), "custom-");

        assertThat(props.connectionTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(props.idleTimeout()).isEqualTo(Duration.ofSeconds(60));
        assertThat(props.maxLifetime()).isEqualTo(Duration.ofMinutes(15));
        assertThat(props.leakDetectionThreshold()).isEqualTo(Duration.ofSeconds(2));
        assertThat(props.poolNamePrefix()).isEqualTo("custom-");
    }
}
