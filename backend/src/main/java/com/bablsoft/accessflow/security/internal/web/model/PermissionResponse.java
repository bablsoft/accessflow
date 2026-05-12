package com.bablsoft.accessflow.security.internal.web.model;

import com.bablsoft.accessflow.core.api.DatasourcePermissionView;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PermissionResponse(
        UUID id,
        UUID datasourceId,
        UUID userId,
        String userEmail,
        String userDisplayName,
        boolean canRead,
        boolean canWrite,
        boolean canDdl,
        Integer rowLimitOverride,
        List<String> allowedSchemas,
        List<String> allowedTables,
        List<String> restrictedColumns,
        Instant expiresAt,
        UUID createdBy,
        Instant createdAt
) {
    public static PermissionResponse from(DatasourcePermissionView view) {
        return new PermissionResponse(
                view.id(),
                view.datasourceId(),
                view.userId(),
                view.userEmail(),
                view.userDisplayName(),
                view.canRead(),
                view.canWrite(),
                view.canDdl(),
                view.rowLimitOverride(),
                view.allowedSchemas(),
                view.allowedTables(),
                view.restrictedColumns(),
                view.expiresAt(),
                view.createdBy(),
                view.createdAt());
    }
}
