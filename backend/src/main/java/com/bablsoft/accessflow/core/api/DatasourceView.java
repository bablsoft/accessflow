package com.bablsoft.accessflow.core.api;

import java.time.Instant;
import java.util.List;
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
        List<ReadReplicaView> readReplicas,
        boolean active,
        Instant createdAt,
        String localDatacenter,
        boolean resultCacheEnabled,
        Integer resultCacheTtlSeconds
) {
    /** One read-replica endpoint as exposed to admins — never carries the password. */
    public record ReadReplicaView(UUID id, String jdbcUrl, String username) {
    }

    public DatasourceView {
        readReplicas = readReplicas == null ? List.of() : List.copyOf(readReplicas);
    }

    /**
     * Backward-compatible constructor taking the pre-AF-457 single-replica pair; a non-blank
     * {@code readReplicaJdbcUrl} maps to a one-endpoint list (no endpoint id), caching defaults off.
     */
    public DatasourceView(
            UUID id, UUID organizationId, String name, DbType dbType, String host, Integer port,
            String databaseName, String username, SslMode sslMode, int connectionPoolSize,
            int maxRowsPerQuery, boolean requireReviewReads, boolean requireReviewWrites,
            UUID reviewPlanId, boolean aiAnalysisEnabled, UUID aiConfigId, boolean textToSqlEnabled,
            UUID customDriverId, String connectorId, String jdbcUrlOverride,
            String readReplicaJdbcUrl, String readReplicaUsername, boolean active,
            Instant createdAt, String localDatacenter) {
        this(id, organizationId, name, dbType, host, port, databaseName, username, sslMode,
                connectionPoolSize, maxRowsPerQuery, requireReviewReads, requireReviewWrites,
                reviewPlanId, aiAnalysisEnabled, aiConfigId, textToSqlEnabled, customDriverId,
                connectorId, jdbcUrlOverride,
                readReplicaJdbcUrl == null || readReplicaJdbcUrl.isBlank()
                        ? List.of()
                        : List.of(new ReadReplicaView(null, readReplicaJdbcUrl, readReplicaUsername)),
                active, createdAt, localDatacenter, false, null);
    }

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
