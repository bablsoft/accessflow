package com.bablsoft.accessflow.proxy.internal.mongo;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class ProxyMongoPropertiesTest {

    @Test
    void appliesDefaultsWhenNull() {
        var props = new ProxyMongoProperties(null, null, null);
        assertThat(props.connectTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(props.serverSelectionTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(props.maxPoolSize()).isEqualTo(10);
    }

    @Test
    void honoursExplicitValuesAndMapsToOptions() {
        var props = new ProxyMongoProperties(Duration.ofSeconds(3), Duration.ofSeconds(7), 25);
        var options = props.toOptions();
        assertThat(options.connectTimeout()).isEqualTo(Duration.ofSeconds(3));
        assertThat(options.serverSelectionTimeout()).isEqualTo(Duration.ofSeconds(7));
        assertThat(options.maxPoolSize()).isEqualTo(25);
    }
}
