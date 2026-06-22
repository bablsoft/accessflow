package com.bablsoft.accessflow.ai.internal;

import java.util.Map;

/**
 * One anomaly emitted by {@link StatisticalAnomalyDetector} for a window. {@code observedValue} /
 * {@code baselineMean} / {@code baselineStddev} are null for categorical-novelty and off-hours
 * anomalies that carry no scalar magnitude. {@code detail} captures the human-readable context
 * (detection method, hour, table, query type, observed vs baseline).
 */
record DetectedAnomaly(
        String feature,
        double score,
        Double observedValue,
        Double baselineMean,
        Double baselineStddev,
        Map<String, Object> detail) {

    DetectedAnomaly {
        detail = detail == null ? Map.of() : Map.copyOf(detail);
    }
}
