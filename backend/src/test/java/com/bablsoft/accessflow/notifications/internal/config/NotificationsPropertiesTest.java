package com.bablsoft.accessflow.notifications.internal.config;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationsPropertiesTest {

    @Test
    void retryDefaultsAppliedWhenAllFieldsNull() {
        var defaults = NotificationsProperties.Retry.defaults();
        assertThat(defaults.first()).isEqualTo(Duration.ofSeconds(30));
        assertThat(defaults.second()).isEqualTo(Duration.ofMinutes(2));
        assertThat(defaults.third()).isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    void retryFillsIndividualNullsButPreservesProvidedValues() {
        var partial = new NotificationsProperties.Retry(Duration.ofSeconds(1), null, null);
        assertThat(partial.first()).isEqualTo(Duration.ofSeconds(1));
        assertThat(partial.second()).isEqualTo(Duration.ofMinutes(2));
        assertThat(partial.third()).isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    void retryAcceptsAllExplicitValues() {
        var explicit = new NotificationsProperties.Retry(
                Duration.ofSeconds(5), Duration.ofSeconds(7), Duration.ofSeconds(9));
        assertThat(explicit.first()).isEqualTo(Duration.ofSeconds(5));
        assertThat(explicit.second()).isEqualTo(Duration.ofSeconds(7));
        assertThat(explicit.third()).isEqualTo(Duration.ofSeconds(9));
    }

    @Test
    void propertiesFallsBackToDefaultRetryWhenNull() {
        var props = new NotificationsProperties(URI.create("https://app.example.test"), null);
        assertThat(props.retry()).isNotNull();
        assertThat(props.retry().first()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void propertiesPreservesProvidedRetry() {
        var custom = new NotificationsProperties.Retry(
                Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(3));
        var props = new NotificationsProperties(URI.create("https://app.example.test"), custom);
        assertThat(props.retry()).isSameAs(custom);
    }
}
