package com.bablsoft.accessflow.core.internal.persistence.repo;

import java.util.UUID;

/**
 * Raw aggregate row returned by {@link QueryRequestStatsRepository#aggregateByDatasource}.
 * Percentiles are {@code null} when no row in the window carried an execution duration.
 */
public record DatasourceQueryStatsRow(
        UUID datasourceId,
        long queriesLast24h,
        long errorsLast24h,
        Double executionMsP50,
        Double executionMsP95) {
}
