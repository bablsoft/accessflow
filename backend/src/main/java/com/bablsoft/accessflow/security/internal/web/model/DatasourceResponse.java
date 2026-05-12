package com.bablsoft.accessflow.security.internal.web.model;

import com.bablsoft.accessflow.core.api.DatasourceView;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.SslMode;

import java.time.Instant;
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
        UUID customDriverId,
        String jdbcUrlOverride,
        boolean active,
        Instant createdAt
) {
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
                view.customDriverId(),
                view.jdbcUrlOverride(),
                view.active(),
                view.createdAt());
    }
}
