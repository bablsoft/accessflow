package com.bablsoft.accessflow.lifecycle.internal.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Tunables for the data-lifecycle module.
 *
 * <ul>
 *   <li>{@code policyScanInterval} — cadence of {@code RetentionPolicyScanJob} (also wired via the
 *       {@code @Scheduled} fixedDelayString fallback so the job has a default at startup).</li>
 * </ul>
 */
@ConfigurationProperties("accessflow.lifecycle")
public record LifecycleProperties(Duration policyScanInterval) {

    public LifecycleProperties {
        if (policyScanInterval == null) {
            policyScanInterval = Duration.ofHours(1);
        }
    }
}
