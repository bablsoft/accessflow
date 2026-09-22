package com.bablsoft.accessflow.schemachange.api;

import java.util.UUID;

/**
 * An environment of the pipeline binds a datasource that no longer exists in the organization —
 * the binding is a bare id with no FK, so a deleted datasource stays bound (#877). Refused rather
 * than skipped: a rung the gate cannot see is a rung the ladder gate (#880) cannot count either.
 * Mapped to HTTP 409.
 */
public final class SchemaChangeSetTargetDatasourceMissingException extends SchemaChangeException {

    private final UUID pipelineId;
    private final UUID datasourceId;

    public SchemaChangeSetTargetDatasourceMissingException(UUID pipelineId, UUID datasourceId) {
        super("Pipeline " + pipelineId + " binds a datasource that no longer exists: " + datasourceId);
        this.pipelineId = pipelineId;
        this.datasourceId = datasourceId;
    }

    public UUID pipelineId() {
        return pipelineId;
    }

    public UUID datasourceId() {
        return datasourceId;
    }
}
