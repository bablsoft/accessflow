package com.bablsoft.accessflow.dashboard.internal.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Tunables for the dashboard module (AF-498). {@code weekly-digest.poll-interval} is how often
 * {@code WeeklyDigestJob} wakes; {@code weekly-digest.period} is the minimum gap between digests for a
 * given user (so the job fires at most once per period regardless of poll cadence / restarts).
 */
@ConfigurationProperties("accessflow.dashboard")
@Validated
public record DashboardProperties(@NestedConfigurationProperty WeeklyDigest weeklyDigest) {

    public DashboardProperties {
        if (weeklyDigest == null) {
            weeklyDigest = new WeeklyDigest(null, null);
        }
    }

    public record WeeklyDigest(Duration pollInterval, Duration period) {
        public WeeklyDigest {
            if (pollInterval == null) {
                pollInterval = Duration.ofDays(1);
            }
            if (period == null) {
                period = Duration.ofDays(7);
            }
        }
    }
}
