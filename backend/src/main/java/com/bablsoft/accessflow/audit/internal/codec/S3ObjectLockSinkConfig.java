package com.bablsoft.accessflow.audit.internal.codec;

import java.time.Duration;

/**
 * Decrypted S3 Object Lock sink settings for dispatch. Never expose outside the audit module.
 * A segment flushes when the batch is full or the oldest pending row is older than
 * {@code segmentMaxAge}; each object is uploaded with the {@code retentionMode} lock held for
 * {@code retentionDays} days.
 */
public record S3ObjectLockSinkConfig(
        String bucket,
        String region,
        String prefix,
        String accessKeyId,
        String secretAccessKeyPlain,
        String endpoint,
        RetentionMode retentionMode,
        int retentionDays,
        Duration segmentMaxAge) {

    public static final String DEFAULT_PREFIX = "audit/";
    public static final Duration DEFAULT_SEGMENT_MAX_AGE = Duration.ofMinutes(15);

    public enum RetentionMode { COMPLIANCE, GOVERNANCE }
}
