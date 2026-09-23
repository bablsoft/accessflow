package com.bablsoft.accessflow.schemachange.internal.web;

import com.bablsoft.accessflow.schemachange.api.SchemaDriftBaseline;
import com.bablsoft.accessflow.schemachange.api.SchemaDriftConfigView;

import java.time.Instant;
import java.util.UUID;

/** One pipeline's drift configuration. A null {@code id} means it has never been configured. */
public record SchemaDriftConfigResponse(
        UUID id,
        UUID pipelineId,
        boolean enabled,
        SchemaDriftBaseline baseline,
        UUID baselineEnvironmentId,
        int scanIntervalHours,
        Instant lastScanAt,
        String lastScanError) {

    static SchemaDriftConfigResponse from(SchemaDriftConfigView view) {
        return new SchemaDriftConfigResponse(view.id(), view.pipelineId(), view.enabled(), view.baseline(),
                view.baselineEnvironmentId(), view.scanIntervalHours(), view.lastScanAt(),
                view.lastScanError());
    }
}
