package com.bablsoft.accessflow.deploygov.internal.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeploygovPropertiesTest {

    @Test
    void bindsEveryKey() {
        var source = new MapConfigurationPropertySource(Map.of(
                "accessflow.deploygov.timeout-check", "PT2M",
                "accessflow.deploygov.release-check", "PT30S",
                "accessflow.deploygov.escalation-poll-interval", "PT10M",
                "accessflow.deploygov.verification-check", "PT1M",
                "accessflow.deploygov.verification-request-timeout", "PT5S",
                "accessflow.deploygov.verification-allowed-hosts", "svc.internal,prom.internal"));

        var properties = new Binder(source)
                .bind("accessflow.deploygov", DeploygovProperties.class).get();

        assertThat(properties.timeoutCheck()).isEqualTo(Duration.ofMinutes(2));
        assertThat(properties.releaseCheck()).isEqualTo(Duration.ofSeconds(30));
        assertThat(properties.escalationPollInterval()).isEqualTo(Duration.ofMinutes(10));
        assertThat(properties.verificationCheck()).isEqualTo(Duration.ofMinutes(1));
        assertThat(properties.verificationRequestTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(properties.verificationAllowedHosts())
                .containsExactly("svc.internal", "prom.internal");
    }

    @Test
    void appliesDefaultsWhenUnset() {
        var source = new MapConfigurationPropertySource(Map.of(
                "accessflow.deploygov.release-check", "PT45S"));

        var properties = new Binder(source)
                .bind("accessflow.deploygov", DeploygovProperties.class).get();

        assertThat(properties.timeoutCheck()).isEqualTo(Duration.ofMinutes(5));
        assertThat(properties.releaseCheck()).isEqualTo(Duration.ofSeconds(45));
        assertThat(properties.escalationPollInterval()).isEqualTo(Duration.ofMinutes(5));
        assertThat(properties.verificationCheck()).isEqualTo(Duration.ofSeconds(30));
        assertThat(properties.verificationRequestTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(properties.verificationAllowedHosts()).isEmpty();
    }

    @Test
    void compactConstructorDefaultsEveryNull() {
        var properties = new DeploygovProperties(null, null, null, null, null, null);

        assertThat(properties.timeoutCheck()).isEqualTo(Duration.ofMinutes(5));
        assertThat(properties.releaseCheck()).isEqualTo(Duration.ofMinutes(1));
        assertThat(properties.escalationPollInterval()).isEqualTo(Duration.ofMinutes(5));
        assertThat(properties.verificationCheck()).isEqualTo(Duration.ofSeconds(30));
        assertThat(properties.verificationRequestTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(properties.verificationAllowedHosts()).isEmpty();
    }

    @Test
    void allowedHostsAreDefensivelyCopied() {
        var hosts = new ArrayList<>(List.of("svc.internal"));
        var properties = new DeploygovProperties(null, null, null, null, null, hosts);
        hosts.add("other.internal");

        assertThat(properties.verificationAllowedHosts()).containsExactly("svc.internal");
        assertThatThrownBy(() -> properties.verificationAllowedHosts().add("x"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void emptyAllowedHostsBindAsAnEmptyList() {
        var source = new MapConfigurationPropertySource(Map.of(
                "accessflow.deploygov.verification-allowed-hosts", ""));

        var properties = new Binder(source)
                .bind("accessflow.deploygov", DeploygovProperties.class).get();

        assertThat(properties.verificationAllowedHosts()).isEmpty();
    }

    @Test
    void aNonPositiveVerificationRequestTimeoutFallsBackToTheDefault() {
        for (var timeout : new Duration[] {Duration.ZERO, Duration.ofSeconds(-1)}) {
            var properties = new DeploygovProperties(null, null, null, null, timeout, null);
            assertThat(properties.verificationRequestTimeout()).isEqualTo(Duration.ofSeconds(10));
        }
    }
}
