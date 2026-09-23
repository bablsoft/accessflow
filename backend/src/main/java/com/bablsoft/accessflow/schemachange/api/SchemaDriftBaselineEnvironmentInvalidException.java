package com.bablsoft.accessflow.schemachange.api;

import java.util.UUID;

/**
 * The environment designated as the drift baseline is not usable as one: it is missing, is not on
 * this pipeline, or binds no datasource. Mapped to HTTP 422. The designation is pipeline-wide, so the
 * designated environment scanning itself is not a write-time error; the scan records it as
 * {@code BASELINE_ENVIRONMENT_IS_TARGET}.
 *
 * <p>The write path refuses these up front so an operator finds out immediately, rather than
 * discovering hours later that every scan recorded a reason instead of findings. The scan path
 * re-checks anyway, because an environment can be edited after the configuration was saved.
 */
public final class SchemaDriftBaselineEnvironmentInvalidException extends SchemaChangeException {

    private final UUID pipelineId;
    private final UUID baselineEnvironmentId;

    public SchemaDriftBaselineEnvironmentInvalidException(UUID pipelineId, UUID baselineEnvironmentId) {
        super("Invalid schema drift baseline environment " + baselineEnvironmentId
                + " for pipeline " + pipelineId);
        this.pipelineId = pipelineId;
        this.baselineEnvironmentId = baselineEnvironmentId;
    }

    public UUID pipelineId() {
        return pipelineId;
    }

    public UUID baselineEnvironmentId() {
        return baselineEnvironmentId;
    }
}
