package com.bablsoft.accessflow.deploygov.internal.verification;

import com.bablsoft.accessflow.deploygov.api.DeploymentVerificationConfig;
import com.bablsoft.accessflow.deploygov.api.HttpHealthCheck;
import com.bablsoft.accessflow.deploygov.api.IllegalDeploymentVerificationConfigException;
import com.bablsoft.accessflow.deploygov.api.PromqlThresholdCheck;
import com.bablsoft.accessflow.deploygov.api.VerificationCheck;
import com.bablsoft.accessflow.deploygov.api.VerificationComparator;
import com.bablsoft.accessflow.deploygov.internal.config.DeploygovProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeploymentVerificationConfigValidatorTest {

    private static InetAddress[] resolve(String host) throws UnknownHostException {
        return switch (host) {
            case "svc.example.com", "prom.internal" -> new InetAddress[] {InetAddress.getByName("10.1.2.3")};
            case "loop.example.com" -> new InetAddress[] {InetAddress.getByName("127.0.0.1")};
            default -> throw new UnknownHostException(host);
        };
    }

    private static DeploymentVerificationConfigValidator validator(List<String> allowedHosts) {
        var properties = new DeploygovProperties(null, null, null, null, null, allowedHosts);
        return new DeploymentVerificationConfigValidator(
                new VerificationTargetPolicy(properties, DeploymentVerificationConfigValidatorTest::resolve));
    }

    private final DeploymentVerificationConfigValidator validator = validator(List.of());

    private static HttpHealthCheck http(String name, String url) {
        return new HttpHealthCheck(name, url, 200, null);
    }

    private static PromqlThresholdCheck promql(String name, String url) {
        return new PromqlThresholdCheck(name, url, "up", VerificationComparator.GTE, 1, true);
    }

    private static DeploymentVerificationConfig config(Duration window, Duration interval, int threshold,
                                                       VerificationCheck... checks) {
        return new DeploymentVerificationConfig(window, interval, threshold, List.of(checks));
    }

    private static DeploymentVerificationConfig valid(VerificationCheck... checks) {
        return config(Duration.ofMinutes(15), Duration.ofMinutes(1), 3, checks);
    }

    private void assertReason(DeploymentVerificationConfig config, String reason, String checkName) {
        assertThatThrownBy(() -> validator.validate(config))
                .isInstanceOfSatisfying(IllegalDeploymentVerificationConfigException.class, ex -> {
                    org.assertj.core.api.Assertions.assertThat(ex.getReason()).isEqualTo(reason);
                    org.assertj.core.api.Assertions.assertThat(ex.getCheckName()).isEqualTo(checkName);
                });
    }

    @Test
    void acceptsAValidConfig() {
        assertThatCode(() -> validator.validate(valid(http("health", "https://svc.example.com/healthz"),
                promql("errors", "http://prom.internal:9090")))).doesNotThrowAnyException();
    }

    @Test
    void acceptsTheBoundaryDurations() {
        assertThatCode(() -> validator.validate(config(Duration.ofMinutes(1), Duration.ofSeconds(30), 2,
                http("health", "https://svc.example.com")))).doesNotThrowAnyException();
        assertThatCode(() -> validator.validate(config(Duration.ofHours(24), Duration.ofHours(24), 1,
                http("health", "https://svc.example.com")))).doesNotThrowAnyException();
    }

    @Test
    void windowMustBeBetweenOneMinuteAndOneDay() {
        var check = http("health", "https://svc.example.com");
        assertReason(config(Duration.ofSeconds(59), Duration.ofSeconds(30), 1, check),
                DeploymentVerificationConfigValidator.WINDOW_OUT_OF_RANGE, null);
        assertReason(config(Duration.ofHours(24).plusSeconds(1), Duration.ofMinutes(1), 1, check),
                DeploymentVerificationConfigValidator.WINDOW_OUT_OF_RANGE, null);
        assertReason(config(null, Duration.ofMinutes(1), 1, check),
                DeploymentVerificationConfigValidator.WINDOW_OUT_OF_RANGE, null);
    }

    @Test
    void sampleIntervalMustBeBetweenThirtySecondsAndTheWindow() {
        var check = http("health", "https://svc.example.com");
        assertReason(config(Duration.ofMinutes(5), Duration.ofSeconds(29), 1, check),
                DeploymentVerificationConfigValidator.SAMPLE_INTERVAL_OUT_OF_RANGE, null);
        assertReason(config(Duration.ofMinutes(5), Duration.ofMinutes(6), 1, check),
                DeploymentVerificationConfigValidator.SAMPLE_INTERVAL_OUT_OF_RANGE, null);
        assertReason(config(Duration.ofMinutes(5), null, 1, check),
                DeploymentVerificationConfigValidator.SAMPLE_INTERVAL_OUT_OF_RANGE, null);
    }

    @Test
    void failureThresholdMustBeReachableWithinTheWindow() {
        var check = http("health", "https://svc.example.com");
        // 5 min / 2 min = 2 samples (floor): a threshold of 3 can never be reached.
        assertReason(config(Duration.ofMinutes(5), Duration.ofMinutes(2), 3, check),
                DeploymentVerificationConfigValidator.FAILURE_THRESHOLD_EXCEEDS_SAMPLES, null);
        assertThatCode(() -> validator.validate(config(Duration.ofMinutes(5), Duration.ofMinutes(2), 2, check)))
                .doesNotThrowAnyException();
    }

    @Test
    void checkNamesMustBeUnique() {
        assertReason(valid(http("health", "https://svc.example.com"), promql("health", "http://prom.internal")),
                DeploymentVerificationConfigValidator.DUPLICATE_CHECK_NAME, "health");
    }

    @ParameterizedTest
    @ValueSource(strings = {"not a url", "svc.example.com/healthz", "mailto:ops@example.com", ""})
    void everyUrlMustBeAnAbsoluteUri(String url) {
        assertReason(valid(http("health", url)), DeploymentVerificationConfigValidator.INVALID_URL, "health");
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://prom.internal:9090/?tenant=a", "http://prom.internal:9090/#x"})
    void aPrometheusUrlMayNotCarryAQueryOrFragment(String url) {
        assertReason(valid(promql("errors", url)), DeploymentVerificationConfigValidator.INVALID_URL, "errors");
    }

    @Test
    void aHealthUrlMayCarryAQuery() {
        assertThatCode(() -> validator.validate(valid(http("health", "https://svc.example.com/healthz?full=1"))))
                .doesNotThrowAnyException();
    }

    @Test
    void aNullUrlIsInvalid() {
        assertReason(valid(promql("errors", null)), DeploymentVerificationConfigValidator.INVALID_URL, "errors");
    }

    @Test
    void everyUrlMustPassTheTargetPolicy() {
        assertReason(valid(http("health", "https://loop.example.com/healthz")),
                VerificationTargetPolicy.RESTRICTED_ADDRESS, "health");
        assertReason(valid(http("health", "https://svc.example.com"), promql("errors", "ftp://prom.internal")),
                VerificationTargetPolicy.SCHEME_NOT_ALLOWED, "errors");
    }

    @Test
    void aHostThatDoesNotResolveFailsAtSaveTime() {
        assertReason(valid(http("health", "https://typo.example.com/healthz")),
                VerificationTargetPolicy.HOST_UNRESOLVABLE, "health");
    }

    @Test
    void theAllowListIsEnforcedAtSaveTime() {
        var pinned = validator(List.of("example.com"));

        assertThatThrownBy(() -> pinned.validate(valid(promql("errors", "http://prom.internal:9090"))))
                .isInstanceOfSatisfying(IllegalDeploymentVerificationConfigException.class, ex ->
                        org.assertj.core.api.Assertions.assertThat(ex.getReason())
                                .isEqualTo(VerificationTargetPolicy.HOST_NOT_ALLOWED));
        assertThatCode(() -> pinned.validate(valid(http("health", "https://svc.example.com"))))
                .doesNotThrowAnyException();
    }
}
