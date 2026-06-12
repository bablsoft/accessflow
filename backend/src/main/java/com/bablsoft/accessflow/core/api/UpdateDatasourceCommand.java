package com.bablsoft.accessflow.core.api;

import java.util.UUID;

public record UpdateDatasourceCommand(
        String name,
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
        Boolean clearAiConfig,
        String jdbcUrlOverride,
        String readReplicaJdbcUrl,
        String readReplicaUsername,
        String readReplicaPassword,
        Boolean active,
        String localDatacenter
) {
    /** Backward-compatible constructor for non-Cassandra dialects (no {@code localDatacenter}). */
    public UpdateDatasourceCommand(
            String name, String host, Integer port, String databaseName, String username,
            String password, SslMode sslMode, Integer connectionPoolSize, Integer maxRowsPerQuery,
            Boolean requireReviewReads, Boolean requireReviewWrites, UUID reviewPlanId,
            Boolean aiAnalysisEnabled, UUID aiConfigId, Boolean textToSqlEnabled,
            Boolean clearAiConfig, String jdbcUrlOverride, String readReplicaJdbcUrl,
            String readReplicaUsername, String readReplicaPassword, Boolean active) {
        this(name, host, port, databaseName, username, password, sslMode, connectionPoolSize,
                maxRowsPerQuery, requireReviewReads, requireReviewWrites, reviewPlanId,
                aiAnalysisEnabled, aiConfigId, textToSqlEnabled, clearAiConfig, jdbcUrlOverride,
                readReplicaJdbcUrl, readReplicaUsername, readReplicaPassword, active, null);
    }
}
