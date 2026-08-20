package com.bablsoft.accessflow.audit.api;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Cross-module read of an {@code audit_sinks} row, including delivery health. Sensitive config
 * fields (HEC token, HMAC secret, S3 secret access key) are replaced with the masked placeholder
 * {@code "********"} so this view is safe to return from APIs.
 *
 * <p>{@code behindCount} is the number of audit rows past the sink's cursor, computed with a
 * capped keyset count; {@code behindCountCapped} means the true backlog exceeds the cap and
 * {@code behindCount} holds the cap value.
 */
public record AuditSinkView(
        UUID id,
        UUID organizationId,
        AuditSinkType type,
        String name,
        Map<String, Object> config,
        boolean enabled,
        Instant cursorCreatedAt,
        Instant lastSuccessAt,
        String lastError,
        int consecutiveFailures,
        Instant nextAttemptAt,
        long behindCount,
        boolean behindCountCapped,
        Instant createdAt,
        Instant updatedAt) {
}
