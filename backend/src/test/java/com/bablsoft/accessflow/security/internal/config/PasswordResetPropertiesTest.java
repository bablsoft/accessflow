package com.bablsoft.accessflow.security.internal.config;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class PasswordResetPropertiesTest {

    @Test
    void defaultsAppliedWhenAllFieldsNull() {
        var props = new PasswordResetProperties(null, null);

        assertThat(props.ttl()).isEqualTo(Duration.ofHours(1));
        assertThat(props.resetBaseUrl()).isEqualTo(URI.create("http://localhost:5173"));
    }

    @Test
    void preservesProvidedValues() {
        var props = new PasswordResetProperties(Duration.ofMinutes(30),
                URI.create("https://accessflow.example.com"));

        assertThat(props.ttl()).isEqualTo(Duration.ofMinutes(30));
        assertThat(props.resetBaseUrl()).isEqualTo(URI.create("https://accessflow.example.com"));
    }
}
