package com.bablsoft.accessflow.security.internal.config;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class InvitationPropertiesTest {

    @Test
    void defaultsAppliedWhenAllFieldsNull() {
        var props = new InvitationProperties(null, null);

        assertThat(props.ttl()).isEqualTo(Duration.ofDays(7));
        assertThat(props.acceptBaseUrl()).isEqualTo(URI.create("http://localhost:5173"));
    }

    @Test
    void preservesProvidedValues() {
        var props = new InvitationProperties(Duration.ofHours(48),
                URI.create("https://accessflow.example.com"));

        assertThat(props.ttl()).isEqualTo(Duration.ofHours(48));
        assertThat(props.acceptBaseUrl()).isEqualTo(URI.create("https://accessflow.example.com"));
    }
}
