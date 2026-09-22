package com.bablsoft.accessflow.schemachange.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * An authored change set with its ordered statements (#878, epic #870). {@code statementsChecksum}
 * is null for a set without statements; {@code statements} is never null. {@code reviewWarnings}
 * carries the {@code WARN} deterministic-SQL-review findings the validation gate produced on the
 * write that returned this view ({@code create} / {@code replaceStatements}, #879) — every read
 * returns it empty, nothing is persisted.
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
        List<SchemaChangeSetStatementView> statements,
        List<SchemaChangeStatementFinding> reviewWarnings
) {
    public SchemaChangeSetView {
        statements = statements == null ? List.of() : List.copyOf(statements);
        reviewWarnings = reviewWarnings == null ? List.of() : List.copyOf(reviewWarnings);
    }
}
