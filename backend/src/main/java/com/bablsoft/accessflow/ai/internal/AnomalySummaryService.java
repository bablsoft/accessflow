package com.bablsoft.accessflow.ai.internal;

import com.bablsoft.accessflow.ai.internal.config.AnomalyDetectionProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

/**
 * Produces the optional natural-language explanation persisted on a {@code behavior_anomaly} row
 * (UBA, AF-383). On by default ({@code accessflow.ai.anomaly.summary-enabled=true}); fully fail-safe
 * — when disabled, when the org has no usable {@code ai_config}, or on any provider error it returns
 * {@link Optional#empty()} so the anomaly is still persisted without a summary.
 *
 * <p>The holder is resolved through an {@link ObjectProvider} (lazy, by-name) rather than injected
 * by concrete type: integration tests that {@code @MockitoBean AiAnalyzerStrategy} replace the
 * {@code aiAnalyzerStrategyHolder} bean with a bare interface mock, which is not assignable to the
 * concrete {@code AiAnalyzerStrategyHolder} field. Lazy resolution keeps those contexts loadable
 * (the holder is only touched when a summary is actually generated).
 */
@Service
@RequiredArgsConstructor
class AnomalySummaryService {

    private final ObjectProvider<AiAnalyzerStrategyHolder> strategyHolder;
    private final AnomalyDetectionProperties properties;

    Optional<String> summarize(UUID organizationId, DetectedAnomaly anomaly, String userLabel,
                               String datasourceLabel) {
        if (!properties.summaryEnabled()) {
            return Optional.empty();
        }
        return strategyHolder.getObject().summarizeFreeform(organizationId,
                buildPrompt(anomaly, userLabel, datasourceLabel));
    }

    private String buildPrompt(DetectedAnomaly anomaly, String userLabel, String datasourceLabel) {
        var sb = new StringBuilder();
        sb.append("User: ").append(userLabel).append('\n');
        sb.append("Datasource: ").append(datasourceLabel).append('\n');
        sb.append("Anomalous feature: ").append(anomaly.feature()).append('\n');
        if (anomaly.observedValue() != null) {
            sb.append("Observed value: ").append(anomaly.observedValue()).append('\n');
        }
        if (anomaly.baselineMean() != null) {
            sb.append("Baseline mean: ").append(anomaly.baselineMean());
            if (anomaly.baselineStddev() != null) {
                sb.append(" (stddev ").append(anomaly.baselineStddev()).append(')');
            }
            sb.append('\n');
        }
        sb.append("Deviation score: ").append(anomaly.score()).append('\n');
        sb.append("Detection context: ").append(anomaly.detail());
        return sb.toString();
    }
}
