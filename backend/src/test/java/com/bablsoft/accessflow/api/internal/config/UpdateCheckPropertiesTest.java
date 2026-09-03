package com.bablsoft.accessflow.api.internal.config;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UpdateCheckPropertiesTest {

    @Test
    void appliesDefaultsWhenAllNull() {
        var props = new UpdateCheckProperties(null, null, null, null);

        assertThat(props.enabled()).isTrue();
        assertThat(props.url()).isEqualTo(URI.create("https://accessflow.io/version.json"));
        assertThat(props.ttl()).isEqualTo(Duration.ofHours(24));
        assertThat(props.timeout()).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    void keepsExplicitValues() {
        var props = new UpdateCheckProperties(false, URI.create("https://fork.example/v.json"),
                Duration.ofHours(1), Duration.ofSeconds(2));

        assertThat(props.enabled()).isFalse();
        assertThat(props.url()).isEqualTo(URI.create("https://fork.example/v.json"));
        assertThat(props.ttl()).isEqualTo(Duration.ofHours(1));
        assertThat(props.timeout()).isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    void rejectsZeroTtl() {
        assertThatThrownBy(() -> new UpdateCheckProperties(null, null, Duration.ZERO, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("accessflow.updates.ttl");
    }

    @Test
    void rejectsNegativeTimeout() {
        assertThatThrownBy(() -> new UpdateCheckProperties(null, null, null, Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("accessflow.updates.timeout");
    }
}
