package com.bablsoft.accessflow.access.internal.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Tunables for the access (JIT) module.
 *
 * <ul>
 *   <li>{@code grantExpiryPollInterval} — cadence of {@code AccessGrantExpiryJob} (also wired via
 *       the {@code @Scheduled} fixedDelayString fallback so the job has a default at startup).</li>
 *   <li>{@code minDuration}/{@code maxDuration} — inclusive bounds the requested access duration
 *       must fall within; enforced in {@code DefaultAccessRequestService}.</li>
 * </ul>
 */
@ConfigurationProperties("accessflow.access")
public record AccessProperties(
        Duration grantExpiryPollInterval,
        Duration minDuration,
        Duration maxDuration) {

    public AccessProperties {
        if (grantExpiryPollInterval == null) {
            grantExpiryPollInterval = Duration.ofMinutes(5);
        }
        if (minDuration == null) {
            minDuration = Duration.ofMinutes(15);
        }
        if (maxDuration == null) {
            maxDuration = Duration.ofDays(30);
        }
    }
}
