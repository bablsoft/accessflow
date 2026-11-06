package com.bablsoft.accessflow.apigov.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One drift finding. {@code before} is {@code null} for {@link ApiSchemaDriftKind#OPERATION_ADDED},
 * {@code after} for {@link ApiSchemaDriftKind#OPERATION_REMOVED}. The affected grant ids are only set
 * for a removed operation: the user and group grants whose allowed operations name it.
 */
public record ApiSchemaDriftFindingView(
        UUID id,
        UUID schemaId,
        UUID revisionId,
        ApiSchemaDriftKind kind,
        String operationId,
        ApiOperation before,
        ApiOperation after,
        int affectedGrantCount,
        List<UUID> affectedUserGrantIds,
        List<UUID> affectedGroupGrantIds,
        ApiSchemaDriftStatus status,
        Instant createdAt,
        Instant resolvedAt,
        UUID resolvedBy) {
}
