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
        Boolean active
) {}
