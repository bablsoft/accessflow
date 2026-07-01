package com.bablsoft.accessflow.lifecycle.internal.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Tunables for the data-lifecycle module.
 *
 * <ul>
 *   <li>{@code policyScanInterval} — cadence of {@code RetentionPolicyScanJob} (which stages
 *       eligible runs; cron policies are staged only when their schedule is due).</li>
 *   <li>{@code policyExecutionInterval} — cadence of {@code RetentionPolicyExecutionJob}, which
 *       drains STAGED retention runs and applies the action through the proxy (AF-519).</li>
 *   <li>{@code erasureExecutionInterval} — cadence of {@code ErasureExecutionJob}.</li>
 *   <li>{@code reviewTimeoutPollInterval} — cadence of {@code ErasureReviewTimeoutJob}, which
 *       auto-rejects erasure requests stuck in review past {@code reviewTimeout} (AF-519).</li>
 *   <li>{@code reviewTimeout} — how long an erasure request may sit in {@code PENDING_REVIEW}
 *       before it is auto-rejected.</li>
 * </ul>
 */
@ConfigurationProperties("accessflow.lifecycle")
public record LifecycleProperties(Duration policyScanInterval, Duration policyExecutionInterval,
                                  Duration erasureExecutionInterval, Duration reviewTimeoutPollInterval,
                                  Duration reviewTimeout) {

    public LifecycleProperties {
        if (policyScanInterval == null) {
            policyScanInterval = Duration.ofHours(1);
        }
        if (policyExecutionInterval == null) {
            policyExecutionInterval = Duration.ofMinutes(5);
        }
        if (erasureExecutionInterval == null) {
            erasureExecutionInterval = Duration.ofMinutes(1);
        }
        if (reviewTimeoutPollInterval == null) {
            reviewTimeoutPollInterval = Duration.ofMinutes(5);
        }
        if (reviewTimeout == null) {
            reviewTimeout = Duration.ofHours(168);
        }
    }
}
