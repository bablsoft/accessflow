package com.bablsoft.accessflow.proxy.api;

import com.bablsoft.accessflow.core.api.DbType;

import java.util.List;
import java.util.UUID;

/**
 * One per-datasource row of the admin health dashboard. Pool gauges are {@code null} when no live
 * HikariCP pool is cached for the datasource (it has not been queried since the last restart or
 * pool eviction). Percentiles are {@code null} when no executed query carried a duration in the
 * trailing 24h window. {@code replicas} carries per-endpoint read-replica health (AF-457) —
 * empty when the datasource has no replicas.
 */
public record DatasourceHealthSnapshot(
        UUID datasourceId,
        String datasourceName,
        DbType dbType,
        boolean active,
        Integer poolActive,
        Integer poolIdle,
        Integer poolWaiting,
        Integer poolTotal,
        Integer poolMax,
        long queriesLast24h,
        Double executionMsP50,
        Double executionMsP95,
        long errorsLast24h,
        List<ReplicaEndpointHealth> replicas) {

    public DatasourceHealthSnapshot {
        replicas = replicas == null ? List.of() : List.copyOf(replicas);
    }

    /** Backward-compatible constructor without replica health (defaults to none). */
    public DatasourceHealthSnapshot(
            UUID datasourceId, String datasourceName, DbType dbType, boolean active,
            Integer poolActive, Integer poolIdle, Integer poolWaiting, Integer poolTotal,
            Integer poolMax, long queriesLast24h, Double executionMsP50, Double executionMsP95,
            long errorsLast24h) {
        this(datasourceId, datasourceName, dbType, active, poolActive, poolIdle, poolWaiting,
                poolTotal, poolMax, queriesLast24h, executionMsP50, executionMsP95, errorsLast24h,
                List.of());
    }
}
