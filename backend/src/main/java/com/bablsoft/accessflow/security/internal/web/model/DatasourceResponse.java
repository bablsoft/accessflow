package com.bablsoft.accessflow.security.internal.web.model;

import com.bablsoft.accessflow.core.api.DatasourceView;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.SslMode;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record DatasourceResponse(
        UUID id,
        UUID organizationId,
        String name,
        DbType dbType,
        String host,
        Integer port,
        String databaseName,
        String username,
        SslMode sslMode,
        int connectionPoolSize,
        int maxRowsPerQuery,
        boolean requireReviewReads,
        boolean requireReviewWrites,
        UUID reviewPlanId,
        boolean aiAnalysisEnabled,
        UUID aiConfigId,
        boolean textToSqlEnabled,
        UUID customDriverId,
        String connectorId,
        String jdbcUrlOverride,
        List<ReadReplicaResponse> readReplicas,
        boolean active,
        Instant createdAt,
        String localDatacenter,
        boolean resultCacheEnabled,
        Integer resultCacheTtlSeconds
) {
    /** One read-replica endpoint — never carries the password. */
    public record ReadReplicaResponse(UUID id, String jdbcUrl, String username) {
        static ReadReplicaResponse from(DatasourceView.ReadReplicaView view) {
            return new ReadReplicaResponse(view.id(), view.jdbcUrl(), view.username());
        }
    }

    public static DatasourceResponse from(DatasourceView view) {
        return new DatasourceResponse(
                view.id(),
                view.organizationId(),
                view.name(),
                view.dbType(),
                view.host(),
                view.port(),
                view.databaseName(),
                view.username(),
                view.sslMode(),
                view.connectionPoolSize(),
                view.maxRowsPerQuery(),
                view.requireReviewReads(),
                view.requireReviewWrites(),
                view.reviewPlanId(),
                view.aiAnalysisEnabled(),
                view.aiConfigId(),
                view.textToSqlEnabled(),
                view.customDriverId(),
                view.connectorId(),
                view.jdbcUrlOverride(),
                view.readReplicas().stream().map(ReadReplicaResponse::from).toList(),
                view.active(),
                view.createdAt(),
                view.localDatacenter(),
                view.resultCacheEnabled(),
                view.resultCacheTtlSeconds());
    }
}
