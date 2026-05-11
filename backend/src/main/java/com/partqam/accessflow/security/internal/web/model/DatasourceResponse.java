package com.partqam.accessflow.security.internal.web.model;

import com.partqam.accessflow.core.api.DatasourceView;
import com.partqam.accessflow.core.api.DbType;
import com.partqam.accessflow.core.api.SslMode;

import java.time.Instant;
import java.util.UUID;

public record DatasourceResponse(
        UUID id,
        UUID organizationId,
        String name,
        DbType dbType,
        String host,
        int port,
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
                view.active(),
                view.createdAt());
    }
}
