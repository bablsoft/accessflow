package com.bablsoft.accessflow.schemachange.api;

import java.time.Instant;
import java.util.UUID;

/**
 * One promotion attempt of a change set to one environment (#878, epic #870). {@code requestGroupId}
 * is null until the promotion service (#880) has created the group; {@code schemaSnapshot} carries
 * the post-apply introspection as JSON and is null before the transition to
 * {@link SchemaChangePromotionStatus#APPLIED}.
 */
public record SchemaChangePromotionView(
        UUID id,
        UUID organizationId,
        UUID changeSetId,
        UUID environmentId,
        UUID datasourceId,
        UUID requestGroupId,
        SchemaChangePromotionStatus status,
        String statementsChecksum,
        UUID promotedBy,
        Instant submittedAt,
        Instant appliedAt,
        String errorMessage,
        String schemaSnapshot,
        Instant snapshotTakenAt
) {
}
