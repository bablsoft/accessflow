package com.bablsoft.accessflow.schemachange.api;

/**
 * Updates a change set's descriptive fields (#878, epic #870). Every field is null-means-unchanged;
 * the statement list is replaced through
 * {@link SchemaChangeSetService#replaceStatements(java.util.UUID, java.util.UUID, java.util.List)}.
 */
public record UpdateSchemaChangeSetCommand(
        String name,
        String description,
        SchemaChangeSetStatus status
) {
}
