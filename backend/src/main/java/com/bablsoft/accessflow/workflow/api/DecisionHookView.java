package com.bablsoft.accessflow.workflow.api;

import java.time.Instant;
import java.util.UUID;

/**
 * An external decision hook (#945). The signing secret is never part of the view; only whether one
 * is stored.
 *
 * @param datasourceId {@code null} for the organization default
 */
public record DecisionHookView(
        UUID id,
        UUID organizationId,
        UUID datasourceId,
        String name,
        String endpointUrl,
        int timeoutMs,
        boolean includeSql,
        boolean enabled,
        boolean secretConfigured,
        long version,
        Instant createdAt,
        Instant updatedAt) {
}
