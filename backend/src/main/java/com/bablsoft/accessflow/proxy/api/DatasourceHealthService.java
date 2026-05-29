package com.bablsoft.accessflow.proxy.api;

import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.PageResponse;

import java.util.UUID;

/**
 * Aggregates per-datasource operational health (live pool gauges + a trailing 24h window of query
 * volume, latency percentiles, and error count) for the admin dashboard. Snapshots are cached
 * briefly per {@code (organizationId, datasourceId)} so the dashboard's auto-refresh does not
 * re-run the aggregate on every poll.
 */
public interface DatasourceHealthService {

    PageResponse<DatasourceHealthSnapshot> snapshot(UUID organizationId, PageRequest pageRequest);
}
