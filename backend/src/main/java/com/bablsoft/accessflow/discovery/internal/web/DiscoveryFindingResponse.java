package com.bablsoft.accessflow.discovery.internal.web;

import com.bablsoft.accessflow.core.api.DataClassification;
import com.bablsoft.accessflow.discovery.api.DiscoveryDetector;
import com.bablsoft.accessflow.discovery.api.DiscoveryFindingStatus;
import com.bablsoft.accessflow.discovery.api.DiscoveryFindingView;

import java.time.Instant;
import java.util.UUID;

public record DiscoveryFindingResponse(
        UUID id,
        String schemaName,
        String tableName,
        String columnName,
        DataClassification classification,
        DiscoveryDetector detector,
        int confidence,
        String sampleRedacted,
        String rationale,
        int matchCount,
        int sampleCount,
        DiscoveryFindingStatus status,
        Instant firstDetectedAt,
        Instant lastDetectedAt,
        UUID decidedBy,
        Instant decidedAt) {

    public static DiscoveryFindingResponse from(DiscoveryFindingView view) {
        return new DiscoveryFindingResponse(view.id(), view.schemaName(), view.tableName(),
                view.columnName(), view.classification(), view.detector(), view.confidence(),
                view.sampleRedacted(), view.rationale(), view.matchCount(), view.sampleCount(),
                view.status(), view.firstDetectedAt(), view.lastDetectedAt(), view.decidedBy(),
                view.decidedAt());
    }
}
