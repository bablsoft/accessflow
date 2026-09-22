package com.bablsoft.accessflow.schemachange.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * An authored change set with its ordered statements (#878, epic #870). {@code statementsChecksum}
 * is null until the authoring service (#879) has computed it; {@code statements} is never null.
 */
public record SchemaChangeSetView(
        UUID id,
        UUID organizationId,
        UUID pipelineId,
        String name,
        String description,
        SchemaChangeSetStatus status,
        String statementsChecksum,
        UUID createdBy,
        Instant createdAt,
        Instant updatedAt,
        List<SchemaChangeSetStatementView> statements
) {
    public SchemaChangeSetView {
        statements = statements == null ? List.of() : List.copyOf(statements);
    }
}
