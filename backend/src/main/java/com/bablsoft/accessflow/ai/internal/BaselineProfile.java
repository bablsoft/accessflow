package com.bablsoft.accessflow.ai.internal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The rolling behavioural profile serialized into {@code behavior_baseline.features} (JSONB). Per
 * scalar feature it keeps a bounded FIFO list of recent per-window observations (the detector
 * computes mean/stddev/p25/p75 from these on demand); {@code hourHistogram} is a cumulative
 * 24-bucket active-hour count; {@code queryTypeFrequencies} / {@code tableFrequencies} are
 * cumulative occurrence maps used for categorical-novelty detection; {@code windowsFolded} is the
 * total number of windows folded in (the min-sample cold-start guard reads this).
 *
 * <p>Immutable — {@link #fold} returns a new instance. Serialized with the AI module's Jackson
 * {@code ObjectMapper}; the compact constructor tolerates partial / legacy JSON (null collections).
 */
record BaselineProfile(
        Map<String, List<Double>> scalars,
        long[] hourHistogram,
        Map<String, Long> queryTypeFrequencies,
        Map<String, Long> tableFrequencies,
        int windowsFolded) {

    static final String QUERY_COUNT = "query_count";
    static final String DISTINCT_TABLES = "distinct_tables";
    static final String ROWS_RETURNED = "rows_returned";
    static final String ERROR_RATE = "error_rate";

    BaselineProfile {
        scalars = scalars == null ? new LinkedHashMap<>() : new LinkedHashMap<>(scalars);
        hourHistogram = normalizeHistogram(hourHistogram);
        queryTypeFrequencies = queryTypeFrequencies == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(queryTypeFrequencies);
        tableFrequencies = tableFrequencies == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(tableFrequencies);
    }

    static BaselineProfile empty() {
        return new BaselineProfile(new LinkedHashMap<>(), new long[24], new LinkedHashMap<>(),
                new LinkedHashMap<>(), 0);
    }

    List<Double> scalar(String feature) {
        return scalars.getOrDefault(feature, List.of());
    }

    /** Fold one window's observations into a new profile, trimming each scalar list to {@code maxSamples}. */
    BaselineProfile fold(WindowFeatures window, int maxSamples) {
        var newScalars = new LinkedHashMap<String, List<Double>>(scalars);
        appendScalar(newScalars, QUERY_COUNT, (double) window.queryCount(), maxSamples);
        appendScalar(newScalars, DISTINCT_TABLES, (double) window.distinctTables(), maxSamples);
        appendScalar(newScalars, ERROR_RATE, window.errorRate(), maxSamples);
        if (window.meanRowsReturned() != null) {
            appendScalar(newScalars, ROWS_RETURNED, window.meanRowsReturned(), maxSamples);
        }
        var newHistogram = hourHistogram.clone();
        int[] windowHistogram = window.hourHistogram();
        for (int h = 0; h < 24 && h < windowHistogram.length; h++) {
            newHistogram[h] += windowHistogram[h];
        }
        var newQueryTypes = new LinkedHashMap<>(queryTypeFrequencies);
        window.queryTypeCounts().forEach((type, count) ->
                newQueryTypes.merge(type, count.longValue(), Long::sum));
        var newTables = new LinkedHashMap<>(tableFrequencies);
        window.tables().forEach(table -> newTables.merge(table, 1L, Long::sum));
        return new BaselineProfile(newScalars, newHistogram, newQueryTypes, newTables,
                windowsFolded + 1);
    }

    private static void appendScalar(Map<String, List<Double>> target, String feature, double value,
                                     int maxSamples) {
        var list = new ArrayList<>(target.getOrDefault(feature, List.of()));
        list.add(value);
        while (list.size() > maxSamples && !list.isEmpty()) {
            list.remove(0);
        }
        target.put(feature, list);
    }

    private static long[] normalizeHistogram(long[] histogram) {
        if (histogram != null && histogram.length == 24) {
            return histogram;
        }
        var fixed = new long[24];
        if (histogram != null) {
            System.arraycopy(histogram, 0, fixed, 0, Math.min(histogram.length, 24));
        }
        return fixed;
    }
}
