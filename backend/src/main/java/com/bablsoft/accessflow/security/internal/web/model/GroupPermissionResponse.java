package com.bablsoft.accessflow.security.internal.web.model;

import com.bablsoft.accessflow.core.api.DatasourceGroupPermissionView;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record GroupPermissionResponse(
        UUID id,
        UUID datasourceId,
        UUID groupId,
        String groupName,
        long memberCount,
        boolean canRead,
        boolean canWrite,
        boolean canDdl,
        boolean canBreakGlass,
        Integer rowLimitOverride,
        List<String> allowedSchemas,
        List<String> allowedTables,
        List<String> restrictedColumns,
        Instant expiresAt,
        UUID createdBy,
        Instant createdAt
) {
    public static GroupPermissionResponse from(DatasourceGroupPermissionView view) {
        return new GroupPermissionResponse(
                view.id(),
                view.datasourceId(),
                view.groupId(),
                view.groupName(),
                view.memberCount(),
                view.canRead(),
                view.canWrite(),
                view.canDdl(),
                view.canBreakGlass(),
                view.rowLimitOverride(),
                view.allowedSchemas(),
                view.allowedTables(),
                view.restrictedColumns(),
                view.expiresAt(),
                view.createdBy(),
                view.createdAt());
    }
}
