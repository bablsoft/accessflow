package com.bablsoft.accessflow.core.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Read model for an organization exposed to the cross-org platform management endpoints (AF-456).
 * Quota fields are nullable; a {@code null} (or {@code 0}) limit means "unlimited".
 */
public record OrganizationView(
        UUID id,
        String name,
        String slug,
        boolean disabled,
        Integer maxDatasources,
        Integer maxUsers,
        Integer maxQueriesPerDay,
        Instant createdAt,
        Instant updatedAt
) {}
