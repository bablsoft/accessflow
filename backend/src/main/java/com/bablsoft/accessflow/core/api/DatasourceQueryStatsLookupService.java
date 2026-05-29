package com.bablsoft.accessflow.core.api;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * Read-only aggregate over {@code query_requests}, used by the datasource health dashboard.
 */
public interface DatasourceQueryStatsLookupService {

    /**
     * @return per-datasource query stats over all requests whose {@code created_at} is strictly
     * after {@code since}. The map contains an entry only for datasources that have at least one
     * matching row; callers treat absent ids as {@link DatasourceQueryStats#empty()}. An empty
     * {@code datasourceIds} yields an empty map.
     */
    Map<UUID, DatasourceQueryStats> statsFor(Collection<UUID> datasourceIds, Instant since);
}
