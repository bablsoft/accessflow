package com.bablsoft.accessflow.core.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record DatasourcePermissionView(
        UUID id,
        UUID datasourceId,
        UUID userId,
        String userEmail,
        String userDisplayName,
        boolean canRead,
        boolean canWrite,
        boolean canDdl,
        boolean canBreakGlass,
        Integer rowLimitOverride,
        List<String> allowedSchemas,
        List<String> allowedTables,
        List<String> restrictedColumns,
        List<String> deniedColumns,
        List<String> deniedSchemas,
        List<String> deniedTables,
        List<QueryShape> deniedShapes,
        Instant expiresAt,
        UUID createdBy,
        Instant createdAt
) {}
