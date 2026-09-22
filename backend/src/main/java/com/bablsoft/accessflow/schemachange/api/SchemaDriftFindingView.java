package com.bablsoft.accessflow.schemachange.api;

import java.time.Instant;
import java.util.UUID;

/** One drifted object path as last observed by a scan (#878, epic #870). */
public record SchemaDriftFindingView(
        UUID id,
        UUID organizationId,
        UUID scanId,
        UUID environmentId,
        String objectPath,
        SchemaDriftFindingKind findingKind,
        String expectedValue,
        String actualValue,
        SchemaDriftFindingStatus status,
        Instant firstDetectedAt,
        Instant lastSeenAt,
        Instant resolvedAt
) {
}
