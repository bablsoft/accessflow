package com.bablsoft.accessflow.scheduling.internal.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Job execution recording (#923), bound from {@code accessflow.scheduling.executions.*}.
 *
 * <p>One constructor only. A second convenience constructor silently unbinds every property.
 *
 * @param enabled               record executions at all. Off turns the recorder into a pass-through;
 *                              the registry still works.
 * @param retention             rows older than this are pruned. Non-positive skips age pruning.
 * @param maxPerJob             each job keeps at most this many newest rows. Non-positive skips the cap.
 * @param retentionPollInterval cadence of {@code JobExecutionRetentionJob}. Read by the
 *                              {@code @Scheduled} placeholder; declared here for documentation.
 * @param summaryWindow         window the per-job success/failure counts and mean duration cover.
 */
@ConfigurationProperties("accessflow.scheduling.executions")
public record JobMonitoringProperties(Boolean enabled, Duration retention, Integer maxPerJob,
                                      Duration retentionPollInterval, Duration summaryWindow) {

    public JobMonitoringProperties {
        enabled = enabled == null || enabled;
        retention = retention == null ? Duration.ofDays(14) : retention;
        maxPerJob = maxPerJob == null ? 500 : maxPerJob;
        retentionPollInterval = retentionPollInterval == null ? Duration.ofHours(6) : retentionPollInterval;
        summaryWindow = summaryWindow == null || summaryWindow.isNegative() || summaryWindow.isZero()
                ? Duration.ofHours(24) : summaryWindow;
    }

    public boolean isEnabled() {
        return enabled;
    }
}
