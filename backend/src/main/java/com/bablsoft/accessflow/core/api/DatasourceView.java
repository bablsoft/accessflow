package com.bablsoft.accessflow.core.api;

import java.time.Instant;
import java.util.UUID;

public record DatasourceView(
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
        String readReplicaJdbcUrl,
        String readReplicaUsername,
        boolean active,
        Instant createdAt,
        String localDatacenter
) {
    /** Backward-compatible constructor for non-Cassandra dialects (no {@code localDatacenter}). */
    public DatasourceView(
            UUID id, UUID organizationId, String name, DbType dbType, String host, Integer port,
            String databaseName, String username, SslMode sslMode, int connectionPoolSize,
            int maxRowsPerQuery, boolean requireReviewReads, boolean requireReviewWrites,
            UUID reviewPlanId, boolean aiAnalysisEnabled, UUID aiConfigId, boolean textToSqlEnabled,
            UUID customDriverId, String connectorId, String jdbcUrlOverride,
            String readReplicaJdbcUrl, String readReplicaUsername, boolean active,
            Instant createdAt) {
        this(id, organizationId, name, dbType, host, port, databaseName, username, sslMode,
                connectionPoolSize, maxRowsPerQuery, requireReviewReads, requireReviewWrites,
                reviewPlanId, aiAnalysisEnabled, aiConfigId, textToSqlEnabled, customDriverId,
                connectorId, jdbcUrlOverride, readReplicaJdbcUrl, readReplicaUsername, active,
                createdAt, null);
    }
}
