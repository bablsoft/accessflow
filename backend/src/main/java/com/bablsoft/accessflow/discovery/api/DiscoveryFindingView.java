package com.bablsoft.accessflow.discovery.api;

import com.bablsoft.accessflow.core.api.DataClassification;

import java.time.Instant;
import java.util.UUID;

/**
 * A proposed sensitive-column classification awaiting an admin decision (AF-623).
 * {@code sampleRedacted} only ever carries a redacted value — raw sampled data never persists.
 * {@code rationale} is populated for {@link DiscoveryDetector#AI} findings only.
 */
public record DiscoveryFindingView(
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
}
