package com.bablsoft.accessflow.schemachange.api;

import java.util.List;
import java.util.UUID;

/**
 * Creates a change set under a pipeline (#878, epic #870). {@code statements} {@code null} means an
 * empty set; the list order is the statement order.
 */
public record CreateSchemaChangeSetCommand(
        UUID pipelineId,
        String name,
        String description,
        List<SchemaChangeSetStatementInput> statements
) {
    public CreateSchemaChangeSetCommand {
        statements = statements == null ? List.of() : List.copyOf(statements);
    }
}
