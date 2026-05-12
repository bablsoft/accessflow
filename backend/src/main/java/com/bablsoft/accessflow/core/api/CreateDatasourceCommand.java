package com.bablsoft.accessflow.core.api;

import java.util.UUID;

public record CreateDatasourceCommand(
        UUID organizationId,
        String name,
        DbType dbType,
        String host,
        int port,
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
        UUID aiConfigId
) {}
