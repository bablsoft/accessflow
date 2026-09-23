package com.bablsoft.accessflow.schemachange.internal.web;

import com.bablsoft.accessflow.schemachange.api.SchemaDriftBaseline;
import com.bablsoft.accessflow.schemachange.api.SchemaDriftScanView;

import java.time.Instant;
import java.util.UUID;

/**
 * One drift scan. {@code applicable = false} means the engine samples rather than reading a catalog
 * and was never contacted — deliberately distinct from an applicable scan that found nothing.
 * {@code errorMessage} is a stable reason code, not prose, so the UI can localize it.
 */
public record SchemaDriftScanResponse(
        UUID id,
        UUID pipelineId,
        UUID environmentId,
        UUID datasourceId,
        SchemaDriftBaseline baseline,
        Instant startedAt,
        Instant finishedAt,
        boolean applicable,
        int findingsCount,
        boolean partial,
        String errorMessage) {

    static SchemaDriftScanResponse from(SchemaDriftScanView view) {
        return new SchemaDriftScanResponse(view.id(), view.pipelineId(), view.environmentId(),
                view.datasourceId(), view.baseline(), view.startedAt(), view.finishedAt(),
                view.applicable(), view.findingsCount(), view.partial(), view.errorMessage());
    }
}
