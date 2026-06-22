package com.bablsoft.accessflow.ai.internal;

import com.bablsoft.accessflow.ai.internal.config.AnomalyDetectionProperties;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class StatisticalAnomalyDetectorTest {

    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant END = Instant.parse("2026-01-01T01:00:00Z");

    private final StatisticalAnomalyDetector detector = new StatisticalAnomalyDetector();
    // Defaults: lookback PT1H, z=3.0, iqr=1.5, minSample=7, maxSamples=90, offHours=0.02, summary=true.
    private final AnomalyDetectionProperties props =
            new AnomalyDetectionProperties(null, 0, 0, 0, 0, -1, null);

    private static WindowFeatures window(int queryCount, int distinctTables, double errorRate,
                                         Double meanRows, Set<String> tables,
                                         Map<String, Integer> queryTypeCounts, Set<Integer> activeHours,
                                         int[] hourHistogram) {
        return new WindowFeatures(START, END, queryCount, hourHistogram, distinctTables, tables,
                queryTypeCounts, meanRows, errorRate, activeHours);
    }

    private static WindowFeatures scalarWindow(int queryCount) {
        return window(queryCount, 0, 0.0, null, Set.of(), new LinkedHashMap<>(), Set.of(), new int[24]);
    }

    /** Builds a baseline whose query_count scalar list is exactly {@code values}, no other features. */
    private static BaselineProfile baselineWithQueryCounts(List<Double> values) {
        var scalars = new LinkedHashMap<String, List<Double>>();
        scalars.put(BaselineProfile.QUERY_COUNT, values);
        return new BaselineProfile(scalars, new long[24], Map.of(), Map.of(), values.size());
    }

    @Test
    void belowMinSampleSizeProducesNoScalarAnomaly() {
        // Only 6 history points (< minSampleSize 7) → scalar detector returns nothing.
        var profile = baselineWithQueryCounts(List.of(1.0, 1.0, 1.0, 1.0, 1.0, 1.0));
        var anomalies = detector.detect(profile, scalarWindow(100), props);
        assertThat(anomalies).isEmpty();
    }

    @Test
    void zScoreFlagsExtremeObservation() {
        var history = List.of(10.0, 11.0, 9.0, 10.0, 12.0, 8.0, 10.0);
        var profile = baselineWithQueryCounts(history);
        var anomalies = detector.detect(profile, scalarWindow(100), props);

        assertThat(anomalies).hasSize(1);
        var a = anomalies.get(0);
        assertThat(a.feature()).isEqualTo(BaselineProfile.QUERY_COUNT);
        assertThat(a.score()).isGreaterThanOrEqualTo(3.0);
        assertThat(a.observedValue()).isEqualTo(100.0);
        assertThat(a.detail()).containsEntry("method", "zscore").containsKey("z");
    }

    @Test
    void zScoreWithinThresholdProducesNoAnomaly() {
        var history = List.of(10.0, 11.0, 9.0, 10.0, 12.0, 8.0, 10.0);
        var profile = baselineWithQueryCounts(history);
        // observed 11 is well within 3 sigma of mean ~10.
        var anomalies = detector.detect(profile, scalarWindow(11), props);
        assertThat(anomalies).isEmpty();
    }

    // The IQR fallback only fires when sample stddev is at or below EPS (1e-9) while the IQR is
    // above it — a razor-thin regime. Cluster the history so the spread keeps stddev <= 1e-9 but
    // p75-p25 > 1e-9 (verified arithmetically: stddev 8.16e-10, IQR 1.0e-9).
    private static final double D = 1e-9;
    private static final List<Double> IQR_HISTORY =
            List.of(10.0, 10.0, 10.0 + D, 10.0 + D, 10.0 + D, 10.0 + 2 * D, 10.0 + 2 * D);

    @Test
    void iqrFenceFlagsOutsideObservationWhenStddevIsBelowEpsilon() {
        var profile = baselineWithQueryCounts(IQR_HISTORY);
        // observed 500 lands far outside the (near-zero-width) IQR fence.
        var anomalies = detector.detect(profile, scalarWindow(500), props);

        assertThat(anomalies).hasSize(1);
        assertThat(anomalies.get(0).detail()).containsEntry("method", "iqr")
                .containsKey("p25").containsKey("p75");
        assertThat(anomalies.get(0).score()).isPositive();
    }

    @Test
    void iqrFenceInsideFenceProducesNoAnomaly() {
        var profile = baselineWithQueryCounts(IQR_HISTORY);
        // observed 10 (the cluster floor) sits inside the near-zero-width IQR fence → no anomaly.
        assertThat(detector.detect(profile, scalarWindow(10), props))
                .noneMatch(a -> a.feature().equals(BaselineProfile.QUERY_COUNT));
    }

    @Test
    void constantBaselineFlagsAnyDeviation() {
        // stddev 0 AND iqr 0 → constant baseline; observed differs → anomaly.
        var history = List.of(10.0, 10.0, 10.0, 10.0, 10.0, 10.0, 10.0);
        var profile = baselineWithQueryCounts(history);
        var anomalies = detector.detect(profile, scalarWindow(11), props);

        assertThat(anomalies).hasSize(1);
        assertThat(anomalies.get(0).detail()).containsEntry("method", "constant_baseline");
        assertThat(anomalies.get(0).score()).isEqualTo(3.0);
    }

    @Test
    void constantBaselineWithMatchingObservationProducesNoAnomaly() {
        var history = List.of(10.0, 10.0, 10.0, 10.0, 10.0, 10.0, 10.0);
        var profile = baselineWithQueryCounts(history);
        var anomalies = detector.detect(profile, scalarWindow(10), props);
        assertThat(anomalies).isEmpty();
    }

    @Test
    void rowsReturnedScalarIsCheckedOnlyWhenPresent() {
        var scalars = new LinkedHashMap<String, List<Double>>();
        scalars.put(BaselineProfile.ROWS_RETURNED,
                List.of(10.0, 10.0, 10.0, 10.0, 10.0, 10.0, 10.0));
        var profile = new BaselineProfile(scalars, new long[24], Map.of(), Map.of(), 7);

        var withRows = window(0, 0, 0.0, 9999.0, Set.of(), new LinkedHashMap<>(), Set.of(), new int[24]);
        assertThat(detector.detect(profile, withRows, props))
                .anyMatch(a -> a.feature().equals(BaselineProfile.ROWS_RETURNED));

        var noRows = window(0, 0, 0.0, null, Set.of(), new LinkedHashMap<>(), Set.of(), new int[24]);
        assertThat(detector.detect(profile, noRows, props))
                .noneMatch(a -> a.feature().equals(BaselineProfile.ROWS_RETURNED));
    }

    @Test
    void categoricalDetectorsGatedBelowMinSampleSize() {
        // windowsFolded 3 < minSampleSize 7 → off-hours / novelty detectors are skipped.
        var hist = new long[24];
        hist[12] = 100; // a populated histogram so off-hours could otherwise fire
        var profile = new BaselineProfile(new LinkedHashMap<>(), hist,
                Map.of("SELECT", 5L), Map.of("known", 5L), 3);
        var window = window(0, 0, 0.0, null, Set.of("brand_new_table"),
                Map.of("DELETE", 1), Set.of(3), new int[24]);

        var anomalies = detector.detect(profile, window, props);
        assertThat(anomalies).isEmpty();
    }

    @Test
    void offHoursFlagsActiveHourWithLowBaselineFrequency() {
        // Heavy daytime baseline; an active hour at 03:00 with zero baseline frequency is off-hours.
        var hist = new long[24];
        hist[9] = 500;
        hist[10] = 500;
        var profile = new BaselineProfile(new LinkedHashMap<>(), hist, Map.of(), Map.of(), 7);
        var window = window(0, 0, 0.0, null, Set.of(), new LinkedHashMap<>(), Set.of(3), new int[24]);

        var anomalies = detector.detect(profile, window, props);

        assertThat(anomalies).hasSize(1);
        var a = anomalies.get(0);
        assertThat(a.feature()).isEqualTo("active_hours");
        assertThat(a.detail()).containsEntry("method", "off_hours").containsEntry("hour", 3);
        // Zero-frequency hour → max off-hours score.
        assertThat(a.score()).isEqualTo(99.0);
    }

    @Test
    void offHoursWithNonZeroButLowFrequencyScoresBelowMax() {
        var hist = new long[24];
        hist[2] = 1;     // very rare hour: freq = 1/1000 = 0.001 <= 0.02
        hist[9] = 999;
        var profile = new BaselineProfile(new LinkedHashMap<>(), hist, Map.of(), Map.of(), 7);
        var window = window(0, 0, 0.0, null, Set.of(), new LinkedHashMap<>(), Set.of(2), new int[24]);

        var anomalies = detector.detect(profile, window, props);

        assertThat(anomalies).hasSize(1);
        var a = anomalies.get(0);
        assertThat(a.feature()).isEqualTo("active_hours");
        // score = offHoursThreshold(0.02) / freq(0.001) = 20, capped at 99.
        assertThat(a.score()).isEqualTo(20.0);
    }

    @Test
    void offHoursNotFlaggedForCommonActiveHour() {
        var hist = new long[24];
        hist[9] = 500;
        hist[10] = 500;
        var profile = new BaselineProfile(new LinkedHashMap<>(), hist, Map.of(), Map.of(), 7);
        // active hour 9 is half the baseline traffic → not off-hours.
        var window = window(0, 0, 0.0, null, Set.of(), new LinkedHashMap<>(), Set.of(9), new int[24]);

        assertThat(detector.detect(profile, window, props))
                .noneMatch(a -> a.feature().equals("active_hours"));
    }

    @Test
    void offHoursSkippedWhenBaselineHistogramEmpty() {
        var profile = new BaselineProfile(new LinkedHashMap<>(), new long[24], Map.of(), Map.of(), 7);
        var window = window(0, 0, 0.0, null, Set.of(), new LinkedHashMap<>(), Set.of(3), new int[24]);
        assertThat(detector.detect(profile, window, props))
                .noneMatch(a -> a.feature().equals("active_hours"));
    }

    @Test
    void novelQueryTypeIsFlagged() {
        var profile = new BaselineProfile(new LinkedHashMap<>(), new long[24],
                Map.of("SELECT", 50L), Map.of(), 7);
        var window = window(0, 0, 0.0, null, Set.of(),
                Map.of("DELETE", 1, "SELECT", 5), Set.of(), new int[24]);

        var anomalies = detector.detect(profile, window, props);

        assertThat(anomalies).hasSize(1);
        var a = anomalies.get(0);
        assertThat(a.feature()).isEqualTo("query_types");
        assertThat(a.detail()).containsEntry("method", "novelty")
                .containsEntry("new_query_types", List.of("DELETE"));
    }

    @Test
    void knownQueryTypesProduceNoNovelty() {
        var profile = new BaselineProfile(new LinkedHashMap<>(), new long[24],
                Map.of("SELECT", 50L, "UPDATE", 10L), Map.of(), 7);
        var window = window(0, 0, 0.0, null, Set.of(),
                Map.of("SELECT", 5, "UPDATE", 2), Set.of(), new int[24]);
        assertThat(detector.detect(profile, window, props))
                .noneMatch(a -> a.feature().equals("query_types"));
    }

    @Test
    void novelTableIsFlagged() {
        var profile = new BaselineProfile(new LinkedHashMap<>(), new long[24],
                Map.of(), Map.of("orders", 20L), 7);
        var window = window(0, 0, 0.0, null, Set.of("orders", "secret_table"),
                new LinkedHashMap<>(), Set.of(), new int[24]);

        var anomalies = detector.detect(profile, window, props);

        assertThat(anomalies).hasSize(1);
        var a = anomalies.get(0);
        assertThat(a.feature()).isEqualTo("new_tables");
        assertThat(a.detail()).containsEntry("method", "novelty")
                .containsEntry("new_tables", List.of("secret_table"));
    }

    @Test
    void knownTablesProduceNoNovelty() {
        var profile = new BaselineProfile(new LinkedHashMap<>(), new long[24],
                Map.of(), Map.of("orders", 20L, "users", 5L), 7);
        var window = window(0, 0, 0.0, null, Set.of("orders"),
                new LinkedHashMap<>(), Set.of(), new int[24]);
        assertThat(detector.detect(profile, window, props))
                .noneMatch(a -> a.feature().equals("new_tables"));
    }
}
