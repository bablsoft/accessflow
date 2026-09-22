package com.bablsoft.accessflow.schemachange.api;

import java.util.UUID;

/** The pipeline a change set names does not exist in the caller's organization. Mapped to HTTP 404. */
public final class SchemaChangePipelineNotFoundException extends SchemaChangeException {

    private final UUID pipelineId;

    public SchemaChangePipelineNotFoundException(UUID pipelineId) {
        super("Deployment pipeline not found for schema change set: " + pipelineId);
        this.pipelineId = pipelineId;
    }

    public UUID pipelineId() {
        return pipelineId;
    }
}
