package com.bablsoft.accessflow.core.api;

import java.util.List;
import java.util.UUID;

/**
 * Partial-update command: {@code null} means "leave unchanged" for every field. For
 * {@code readReplicas} (AF-457), {@code null} keeps the current endpoint list, an empty list
 * deletes all endpoints, and a non-empty list is a full replacement merged by endpoint id (items
 * with a known {@code id} update that row; items without create new rows; stored rows absent from
 * the list are removed).
 */
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
        List<ReplicaEndpointInput> readReplicas,
        Boolean active,
        String localDatacenter,
        String apiKey,
        Boolean resultCacheEnabled,
        Integer resultCacheTtlSeconds
) {
    /**
     * Backward-compatible constructor taking the pre-AF-457 single-replica triple. A {@code null}
     * URL keeps the current replica list, a blank URL clears it, a non-blank URL replaces it with a
     * single endpoint; cache settings stay unchanged.
     */
    public UpdateDatasourceCommand(
            String name, String host, Integer port, String databaseName, String username,
            String password, SslMode sslMode, Integer connectionPoolSize, Integer maxRowsPerQuery,
            Boolean requireReviewReads, Boolean requireReviewWrites, UUID reviewPlanId,
            Boolean aiAnalysisEnabled, UUID aiConfigId, Boolean textToSqlEnabled,
            Boolean clearAiConfig, String jdbcUrlOverride, String readReplicaJdbcUrl,
            String readReplicaUsername, String readReplicaPassword, Boolean active,
            String localDatacenter, String apiKey) {
        this(name, host, port, databaseName, username, password, sslMode, connectionPoolSize,
                maxRowsPerQuery, requireReviewReads, requireReviewWrites, reviewPlanId,
                aiAnalysisEnabled, aiConfigId, textToSqlEnabled, clearAiConfig, jdbcUrlOverride,
                legacyReplicaList(readReplicaJdbcUrl, readReplicaUsername, readReplicaPassword),
                active, localDatacenter, apiKey, null, null);
    }

    /** Backward-compatible constructor for the dialects with no {@code apiKey} (everything but the search engines). */
    public UpdateDatasourceCommand(
            String name, String host, Integer port, String databaseName, String username,
            String password, SslMode sslMode, Integer connectionPoolSize, Integer maxRowsPerQuery,
            Boolean requireReviewReads, Boolean requireReviewWrites, UUID reviewPlanId,
            Boolean aiAnalysisEnabled, UUID aiConfigId, Boolean textToSqlEnabled,
            Boolean clearAiConfig, String jdbcUrlOverride, String readReplicaJdbcUrl,
            String readReplicaUsername, String readReplicaPassword, Boolean active,
            String localDatacenter) {
        this(name, host, port, databaseName, username, password, sslMode, connectionPoolSize,
                maxRowsPerQuery, requireReviewReads, requireReviewWrites, reviewPlanId,
                aiAnalysisEnabled, aiConfigId, textToSqlEnabled, clearAiConfig, jdbcUrlOverride,
                readReplicaJdbcUrl, readReplicaUsername, readReplicaPassword, active,
                localDatacenter, null);
    }

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
                readReplicaJdbcUrl, readReplicaUsername, readReplicaPassword, active, null, null);
    }

    private static List<ReplicaEndpointInput> legacyReplicaList(String jdbcUrl, String username,
                                                                String password) {
        if (jdbcUrl == null) {
            return null;
        }
        if (jdbcUrl.isBlank()) {
            return List.of();
        }
        return List.of(new ReplicaEndpointInput(null, jdbcUrl, username, password));
    }
}
