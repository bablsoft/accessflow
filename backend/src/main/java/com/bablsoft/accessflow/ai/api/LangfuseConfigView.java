package com.bablsoft.accessflow.ai.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Read DTO for {@link LangfuseConfigService}. The {@code secretKeyConfigured} flag is {@code true}
 * whenever a non-empty secret key is stored — the key itself is never returned to callers.
 */
public record LangfuseConfigView(
        UUID id,
        UUID organizationId,
        boolean enabled,
        String host,
        String publicKey,
        boolean secretKeyConfigured,
        boolean tracingEnabled,
        boolean promptManagementEnabled,
        Instant createdAt,
        Instant updatedAt) {
}
