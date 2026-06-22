package com.bablsoft.accessflow.ai.internal;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

/**
 * The behavioural features observed for one (user, datasource) over a single detection window,
 * derived from {@code audit_log} metadata. Scalar features ({@code queryCount},
 * {@code distinctTables}, {@code meanRowsReturned}, {@code errorRate}) feed the z-score / IQR
 * detectors; {@code hourHistogram} / {@code activeHours} feed off-hours detection; {@code tables} /
 * {@code queryTypeCounts} feed categorical-novelty detection. {@code meanRowsReturned} is null when
 * no executed query in the window reported a row count.
 */
record WindowFeatures(
        Instant windowStart,
        Instant windowEnd,
        int queryCount,
        int[] hourHistogram,
        int distinctTables,
        Set<String> tables,
        Map<String, Integer> queryTypeCounts,
        Double meanRowsReturned,
        double errorRate,
        Set<Integer> activeHours) {
}
