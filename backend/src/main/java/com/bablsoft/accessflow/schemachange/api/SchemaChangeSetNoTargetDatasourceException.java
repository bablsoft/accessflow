package com.bablsoft.accessflow.schemachange.api;

import java.util.UUID;

/**
 * Statements were supplied but no environment of the pipeline binds a datasource, so there is no
 * engine to parse them for and no ruleset to review them against. Mapped to HTTP 409.
 */
public final class SchemaChangeSetNoTargetDatasourceException extends SchemaChangeException {

    private final UUID pipelineId;

    public SchemaChangeSetNoTargetDatasourceException(UUID pipelineId) {
        super("No environment of pipeline " + pipelineId + " binds a datasource");
        this.pipelineId = pipelineId;
    }

    public UUID pipelineId() {
        return pipelineId;
    }
}
