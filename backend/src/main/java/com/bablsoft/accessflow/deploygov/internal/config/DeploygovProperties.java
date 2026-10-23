package com.bablsoft.accessflow.deploygov.internal.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Tunables for the deployment-governance module.
 *
 * <ul>
 *   <li>{@code timeoutCheck} — cadence of {@code DeploymentTimeoutJob} (#692).</li>
 *   <li>{@code releaseCheck} — cadence of {@code ScheduledDeploymentReleaseJob} (#693).</li>
 *   <li>{@code escalationPollInterval} — cadence of {@code DeploymentReviewEscalationJob} (#985).</li>
 * </ul>
 *
 * <p>The jobs read these keys directly through their {@code @Scheduled} placeholders; the record is
 * the typed home for the same keys, not a second source. Keep exactly one constructor — a second
 * one on a {@code @ConfigurationProperties} record silently unbinds every property.
 */
@ConfigurationProperties("accessflow.deploygov")
public record DeploygovProperties(Duration timeoutCheck, Duration releaseCheck,
                                  Duration escalationPollInterval) {

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
    }
}
