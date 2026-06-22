package com.bablsoft.accessflow.ai.internal;

import com.bablsoft.accessflow.ai.internal.config.AnomalyDetectionProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Compares a window's {@link WindowFeatures} against the {@link BaselineProfile} and emits
 * {@link DetectedAnomaly}s. Scalar features use a z-score test, falling back to an IQR fence when
 * the baseline stddev is ~0 (and to a constant-baseline-broken test when the IQR is also ~0).
 * Categorical novelty (a never-seen query type / table) and off-hours access (an active hour whose
 * baseline frequency is at or below the configured floor) are flagged once the baseline has at least
 * {@code minSampleSize} folded windows. Pure and stateless — never throws.
 */
@Component
public class StatisticalAnomalyDetector {

    private static final double EPS = 1e-9;
    private static final double MAX_OFF_HOURS_SCORE = 99.0;

    List<DetectedAnomaly> detect(BaselineProfile profile, WindowFeatures window,
                                 AnomalyDetectionProperties props) {
        var anomalies = new ArrayList<DetectedAnomaly>();
        detectScalar(anomalies, profile, BaselineProfile.QUERY_COUNT, window.queryCount(), props);
        detectScalar(anomalies, profile, BaselineProfile.DISTINCT_TABLES, window.distinctTables(), props);
        detectScalar(anomalies, profile, BaselineProfile.ERROR_RATE, window.errorRate(), props);
        if (window.meanRowsReturned() != null) {
            detectScalar(anomalies, profile, BaselineProfile.ROWS_RETURNED,
                    window.meanRowsReturned(), props);
        }
        if (profile.windowsFolded() >= props.minSampleSize()) {
            detectOffHours(anomalies, profile, window, props);
            detectNovelTypes(anomalies, profile, window, props);
            detectNovelTables(anomalies, profile, window, props);
        }
        return anomalies;
    }

    private void detectScalar(List<DetectedAnomaly> out, BaselineProfile profile, String feature,
                              double observed, AnomalyDetectionProperties props) {
        List<Double> history = profile.scalar(feature);
        if (history.size() < props.minSampleSize()) {
            return;
        }
        double mean = BehaviorStats.mean(history);
        double stddev = BehaviorStats.sampleStddev(history);
        if (stddev > EPS) {
            double z = (observed - mean) / stddev;
            if (Math.abs(z) >= props.zScoreThreshold()) {
                out.add(new DetectedAnomaly(feature, Math.abs(z), observed, mean, stddev,
                        detail("method", "zscore", "z", round(z))));
            }
            return;
        }
        double p25 = BehaviorStats.percentile(history, 0.25);
        double p75 = BehaviorStats.percentile(history, 0.75);
        double iqr = p75 - p25;
        if (iqr > EPS) {
            double median = BehaviorStats.percentile(history, 0.5);
            double upper = p75 + props.iqrMultiplier() * iqr;
            double lower = p25 - props.iqrMultiplier() * iqr;
            if (observed > upper || observed < lower) {
                double score = Math.abs(observed - median) / iqr;
                out.add(new DetectedAnomaly(feature, score, observed, mean, stddev,
                        detail("method", "iqr", "p25", round(p25), "p75", round(p75))));
            }
            return;
        }
        // Constant baseline (stddev and IQR both ~0): any deviation is anomalous.
        if (Math.abs(observed - mean) > EPS) {
            out.add(new DetectedAnomaly(feature, props.zScoreThreshold(), observed, mean, stddev,
                    detail("method", "constant_baseline")));
        }
    }

    private void detectOffHours(List<DetectedAnomaly> out, BaselineProfile profile,
                                WindowFeatures window, AnomalyDetectionProperties props) {
        long total = 0;
        for (long c : profile.hourHistogram()) {
            total += c;
        }
        if (total == 0) {
            return;
        }
        int rarestHour = -1;
        double rarestFreq = Double.MAX_VALUE;
        for (int hour : window.activeHours()) {
            double freq = (double) profile.hourHistogram()[hour] / total;
            if (freq <= props.offHoursThreshold() && freq < rarestFreq) {
                rarestFreq = freq;
                rarestHour = hour;
            }
        }
        if (rarestHour < 0) {
            return;
        }
        double score = rarestFreq <= EPS
                ? MAX_OFF_HOURS_SCORE
                : Math.min(MAX_OFF_HOURS_SCORE, props.offHoursThreshold() / rarestFreq);
        out.add(new DetectedAnomaly("active_hours", score, null, null, null,
                detail("method", "off_hours", "hour", rarestHour, "frequency", round(rarestFreq))));
    }

    private void detectNovelTypes(List<DetectedAnomaly> out, BaselineProfile profile,
                                  WindowFeatures window, AnomalyDetectionProperties props) {
        var novel = window.queryTypeCounts().keySet().stream()
                .filter(type -> !profile.queryTypeFrequencies().containsKey(type))
                .sorted()
                .toList();
        if (!novel.isEmpty()) {
            out.add(new DetectedAnomaly("query_types", props.zScoreThreshold(), null, null, null,
                    detail("method", "novelty", "new_query_types", novel)));
        }
    }

    private void detectNovelTables(List<DetectedAnomaly> out, BaselineProfile profile,
                                   WindowFeatures window, AnomalyDetectionProperties props) {
        var novel = window.tables().stream()
                .filter(table -> !profile.tableFrequencies().containsKey(table))
                .sorted()
                .toList();
        if (!novel.isEmpty()) {
            out.add(new DetectedAnomaly("new_tables", props.zScoreThreshold(), null, null, null,
                    detail("method", "novelty", "new_tables", novel)));
        }
    }

    private static Map<String, Object> detail(Object... keyValues) {
        var map = new LinkedHashMap<String, Object>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    private static double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
