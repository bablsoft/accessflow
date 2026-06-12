package com.bablsoft.accessflow.core.api;

import java.util.UUID;

public record CreateDatasourceCommand(
        UUID organizationId,
        String name,
        DbType dbType,
        String host,
        Integer port,
        String databaseName,
        String username,
        String password,
        SslMode sslMode,
        Integer connectionPoolSize,
        Integer maxRowsPerQuery,
        Boolean requireReviewReads,
        Boolean requireReviewWrites,
        UUID reviewPlanId,
        Boolean aiAnalysisEnabled,
        UUID aiConfigId,
        Boolean textToSqlEnabled,
        UUID customDriverId,
        String connectorId,
        String jdbcUrlOverride,
        String readReplicaJdbcUrl,
        String readReplicaUsername,
        String readReplicaPassword,
        String localDatacenter,
        String apiKey
) {
    /** Backward-compatible constructor for the dialects with no {@code apiKey} (everything but the search engines). */
    public CreateDatasourceCommand(
            UUID organizationId, String name, DbType dbType, String host, Integer port,
            String databaseName, String username, String password, SslMode sslMode,
            Integer connectionPoolSize, Integer maxRowsPerQuery, Boolean requireReviewReads,
            Boolean requireReviewWrites, UUID reviewPlanId, Boolean aiAnalysisEnabled,
            UUID aiConfigId, Boolean textToSqlEnabled, UUID customDriverId, String connectorId,
            String jdbcUrlOverride, String readReplicaJdbcUrl, String readReplicaUsername,
            String readReplicaPassword, String localDatacenter) {
        this(organizationId, name, dbType, host, port, databaseName, username, password, sslMode,
                connectionPoolSize, maxRowsPerQuery, requireReviewReads, requireReviewWrites,
                reviewPlanId, aiAnalysisEnabled, aiConfigId, textToSqlEnabled, customDriverId,
                connectorId, jdbcUrlOverride, readReplicaJdbcUrl, readReplicaUsername,
                readReplicaPassword, localDatacenter, null);
    }

    /** Backward-compatible constructor for non-Cassandra dialects (no {@code localDatacenter}). */
    public CreateDatasourceCommand(
            UUID organizationId, String name, DbType dbType, String host, Integer port,
            String databaseName, String username, String password, SslMode sslMode,
            Integer connectionPoolSize, Integer maxRowsPerQuery, Boolean requireReviewReads,
            Boolean requireReviewWrites, UUID reviewPlanId, Boolean aiAnalysisEnabled,
            UUID aiConfigId, Boolean textToSqlEnabled, UUID customDriverId, String connectorId,
            String jdbcUrlOverride, String readReplicaJdbcUrl, String readReplicaUsername,
            String readReplicaPassword) {
        this(organizationId, name, dbType, host, port, databaseName, username, password, sslMode,
                connectionPoolSize, maxRowsPerQuery, requireReviewReads, requireReviewWrites,
                reviewPlanId, aiAnalysisEnabled, aiConfigId, textToSqlEnabled, customDriverId,
                connectorId, jdbcUrlOverride, readReplicaJdbcUrl, readReplicaUsername,
                readReplicaPassword, null, null);
    }
}
