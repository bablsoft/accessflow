package com.bablsoft.accessflow.ai.api;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Read DTO for a detected behavioural anomaly (UBA, AF-383). {@code observedValue},
 * {@code baselineMean}, {@code baselineStddev} are null for categorical-novelty / off-hours
 * anomalies that have no scalar magnitude. {@code detail} is the observed-vs-baseline context
 * (hour, table, query type, detection method) as a plain map — no Jackson types so this stays in the
 * pure {@code api} package. {@code aiSummary} is null when the AI explanation is disabled or failed.
 * {@code userDisplayName} / {@code userEmail} / {@code datasourceName} are best-effort enrichments
 * (null when the referenced row was removed).
 */
public record BehaviorAnomalyView(
        UUID id,
        UUID organizationId,
        UUID userId,
        String userDisplayName,
        String userEmail,
        UUID datasourceId,
        String datasourceName,
        String feature,
        double score,
        Double observedValue,
        Double baselineMean,
        Double baselineStddev,
        Map<String, Object> detail,
        String aiSummary,
        BehaviorAnomalyStatus status,
        Instant detectedAt,
        UUID acknowledgedBy,
        Instant acknowledgedAt,
        Instant windowStart,
        Instant windowEnd) {

    public BehaviorAnomalyView {
        detail = detail == null ? Map.of() : Map.copyOf(detail);
    }
}
