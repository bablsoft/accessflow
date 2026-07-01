package com.bablsoft.accessflow.core.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record DatasourceGroupPermissionView(
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
        Instant createdAt) {
}
