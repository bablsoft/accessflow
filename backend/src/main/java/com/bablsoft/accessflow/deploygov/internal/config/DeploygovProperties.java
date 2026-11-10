package com.bablsoft.accessflow.deploygov.internal.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * Tunables for the deployment-governance module.
 *
 * <ul>
 *   <li>{@code timeoutCheck} — cadence of {@code DeploymentTimeoutJob} (#692).</li>
 *   <li>{@code releaseCheck} — cadence of {@code ScheduledDeploymentReleaseJob} (#693).</li>
 *   <li>{@code escalationPollInterval} — cadence of {@code DeploymentReviewEscalationJob} (#985).</li>
 *   <li>{@code verificationCheck} — cadence of the post-deployment verification scan (epic #1022).</li>
 *   <li>{@code verificationRequestTimeout} — timeout of one verification check's HTTP call.</li>
 *   <li>{@code verificationAllowedHosts} — hosts verification checks may reach; empty = none.</li>
 * </ul>
 *
 * <p>The jobs read their cadence keys directly through their {@code @Scheduled} placeholders; the
 * record is the typed home for the same keys, not a second source. The three verification keys
 * (#1023) have no consumer yet. Keep exactly one constructor — a second one on a
 * {@code @ConfigurationProperties} record silently unbinds every property.
 */
@ConfigurationProperties("accessflow.deploygov")
public record DeploygovProperties(Duration timeoutCheck, Duration releaseCheck,
                                  Duration escalationPollInterval, Duration verificationCheck,
                                  Duration verificationRequestTimeout,
                                  List<String> verificationAllowedHosts) {

    public DeploygovProperties {
        if (timeoutCheck == null) {
            timeoutCheck = Duration.ofMinutes(5);
        }
        if (releaseCheck == null) {
            releaseCheck = Duration.ofMinutes(1);
        }
        if (escalationPollInterval == null) {
            escalationPollInterval = Duration.ofMinutes(5);
        }
        if (verificationCheck == null) {
            verificationCheck = Duration.ofSeconds(30);
        }
        if (verificationRequestTimeout == null) {
            verificationRequestTimeout = Duration.ofSeconds(10);
        }
        verificationAllowedHosts = verificationAllowedHosts == null
                ? List.of() : List.copyOf(verificationAllowedHosts);
    }
}
