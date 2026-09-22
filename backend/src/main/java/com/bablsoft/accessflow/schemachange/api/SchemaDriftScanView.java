package com.bablsoft.accessflow.schemachange.api;

import java.time.Instant;
import java.util.UUID;

/**
 * One drift scan of one environment (#878, epic #870). {@code applicable} is false when the engine
 * samples rather than reads a catalog and was therefore not diffed; {@code partial} when the table
 * cap or time budget cut the scan short.
 */
public record SchemaDriftScanView(
        UUID id,
        UUID organizationId,
        UUID pipelineId,
        UUID environmentId,
        UUID datasourceId,
        SchemaDriftBaseline baseline,
        Instant startedAt,
        Instant finishedAt,
        boolean applicable,
        int findingsCount,
        boolean partial,
        String errorMessage
) {
}
