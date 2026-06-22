package com.bablsoft.accessflow.ai.internal.web;

import com.bablsoft.accessflow.ai.api.BehaviorAnomalyStatus;
import com.bablsoft.accessflow.ai.api.BehaviorAnomalyView;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** API response for a behavioural anomaly (UBA, AF-383). Field names are snake_case over the wire. */
public record AnomalyResponse(
        UUID id,
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

    public static AnomalyResponse from(BehaviorAnomalyView view) {
        return new AnomalyResponse(
                view.id(),
                view.userId(),
                view.userDisplayName(),
                view.userEmail(),
                view.datasourceId(),
                view.datasourceName(),
                view.feature(),
                view.score(),
                view.observedValue(),
                view.baselineMean(),
                view.baselineStddev(),
                view.detail(),
                view.aiSummary(),
                view.status(),
                view.detectedAt(),
                view.acknowledgedBy(),
                view.acknowledgedAt(),
                view.windowStart(),
                view.windowEnd());
    }
}
