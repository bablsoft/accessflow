package com.bablsoft.accessflow.security.internal.web.model;

import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.proxy.api.DatasourceHealthSnapshot;
import com.bablsoft.accessflow.proxy.api.ReplicaEndpointHealth;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.UUID;

/**
 * Per-datasource health row. Jackson globally converts camelCase → snake_case; the two windowed
 * counts carry an explicit {@code @JsonProperty} so the digit group is underscore-separated
 * ({@code queries_last_24h} rather than the auto-derived {@code queries_last24h}).
 */
public record DatasourceHealthResponse(
        UUID datasourceId,
        String datasourceName,
        DbType dbType,
        boolean active,
        Integer poolActive,
        Integer poolIdle,
        Integer poolWaiting,
        Integer poolTotal,
        Integer poolMax,
        @JsonProperty("queries_last_24h") long queriesLast24h,
        Double executionMsP50,
        Double executionMsP95,
        @JsonProperty("errors_last_24h") long errorsLast24h,
        List<ReplicaHealthResponse> replicas) {

    /** Health of one read-replica endpoint on this node (AF-457). */
    public record ReplicaHealthResponse(
            UUID endpointId,
            String label,
            boolean healthy,
            Integer poolActive,
            Integer poolTotal) {

        static ReplicaHealthResponse from(ReplicaEndpointHealth health) {
            return new ReplicaHealthResponse(health.endpointId(), health.label(), health.healthy(),
                    health.poolActive(), health.poolTotal());
        }
    }

    public static DatasourceHealthResponse from(DatasourceHealthSnapshot snapshot) {
        return new DatasourceHealthResponse(
                snapshot.datasourceId(),
                snapshot.datasourceName(),
                snapshot.dbType(),
                snapshot.active(),
                snapshot.poolActive(),
                snapshot.poolIdle(),
                snapshot.poolWaiting(),
                snapshot.poolTotal(),
                snapshot.poolMax(),
                snapshot.queriesLast24h(),
                snapshot.executionMsP50(),
                snapshot.executionMsP95(),
                snapshot.errorsLast24h(),
                snapshot.replicas().stream().map(ReplicaHealthResponse::from).toList());
    }
}
