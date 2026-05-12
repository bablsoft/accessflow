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
        UUID customDriverId,
        String jdbcUrlOverride,
        boolean active,
        Instant createdAt
) {}
