package com.bablsoft.accessflow.schemachange.internal.web;

import com.bablsoft.accessflow.schemachange.api.SchemaDriftFindingKind;
import com.bablsoft.accessflow.schemachange.api.SchemaDriftFindingStatus;
import com.bablsoft.accessflow.schemachange.api.SchemaDriftFindingView;

import java.time.Instant;
import java.util.UUID;

/**
 * One drifted object path. {@code objectPath} is {@code schema}, {@code schema.table} or
 * {@code schema.table.column}; {@code expectedValue} is the baseline's side and
 * {@code actualValue} the scanned environment's.
 */
public record SchemaDriftFindingResponse(
        UUID id,
        UUID scanId,
        UUID environmentId,
        String objectPath,
        SchemaDriftFindingKind findingKind,
        String expectedValue,
        String actualValue,
        SchemaDriftFindingStatus status,
        Instant firstDetectedAt,
        Instant lastSeenAt,
        Instant resolvedAt) {

    static SchemaDriftFindingResponse from(SchemaDriftFindingView view) {
        return new SchemaDriftFindingResponse(view.id(), view.scanId(), view.environmentId(),
                view.objectPath(), view.findingKind(), view.expectedValue(), view.actualValue(),
                view.status(), view.firstDetectedAt(), view.lastSeenAt(), view.resolvedAt());
    }
}
