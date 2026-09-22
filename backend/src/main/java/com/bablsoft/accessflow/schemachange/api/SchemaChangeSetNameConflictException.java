package com.bablsoft.accessflow.schemachange.api;

import java.util.UUID;

/**
 * A change set with the same name already exists under the pipeline — the
 * {@code uq_schema_change_sets_org_pipeline_name} constraint. Mapped to HTTP 409.
 */
public final class SchemaChangeSetNameConflictException extends SchemaChangeException {

    private final UUID pipelineId;
    private final String name;

    public SchemaChangeSetNameConflictException(UUID pipelineId, String name) {
        super("Schema change set name already in use under pipeline " + pipelineId + ": " + name);
        this.pipelineId = pipelineId;
        this.name = name;
    }

    public UUID pipelineId() {
        return pipelineId;
    }

    public String name() {
        return name;
    }
}
