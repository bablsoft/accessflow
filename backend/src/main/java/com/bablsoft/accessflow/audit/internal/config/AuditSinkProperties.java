package com.bablsoft.accessflow.audit.internal.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Tunables for external audit-sink draining (#628).
 *
 * <ul>
 *   <li>{@code drainInterval} — cadence of {@code AuditSinkDrainJob}. Read directly by the
 *       {@code @Scheduled} placeholder, listed here for documentation only.</li>
 *   <li>{@code batchSize} — rows per delivery batch; also the S3 segment's maximum row count.</li>
 *   <li>{@code maxBatchesPerTick} — per-sink batch cap per tick, bounding one tick's work so a
 *       deep backlog cannot exhaust the scheduler lock.</li>
 * </ul>
 */
@ConfigurationProperties("accessflow.audit.sinks")
public record AuditSinkProperties(Duration drainInterval, Integer batchSize,
                                  Integer maxBatchesPerTick) {

    public AuditSinkProperties {
        if (drainInterval == null || drainInterval.isNegative() || drainInterval.isZero()) {
            drainInterval = Duration.ofSeconds(30);
        }
        if (batchSize == null || batchSize <= 0) {
            batchSize = 500;
        }
        if (maxBatchesPerTick == null || maxBatchesPerTick <= 0) {
            maxBatchesPerTick = 5;
        }
    }
}
