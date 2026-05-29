package com.bablsoft.accessflow.core.api;

/**
 * Aggregated query-request signals for a single datasource over a trailing window. Percentiles
 * are {@code null} when no executed query carried an execution duration in the window.
 */
public record DatasourceQueryStats(
        long queriesLast24h,
        long errorsLast24h,
        Double executionMsP50,
        Double executionMsP95) {

    public static DatasourceQueryStats empty() {
        return new DatasourceQueryStats(0L, 0L, null, null);
    }
}
