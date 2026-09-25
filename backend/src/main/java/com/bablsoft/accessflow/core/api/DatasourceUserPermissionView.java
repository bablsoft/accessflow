package com.bablsoft.accessflow.core.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record DatasourceUserPermissionView(
        UUID id,
        UUID userId,
        UUID datasourceId,
        boolean canRead,
        boolean canWrite,
        boolean canDdl,
        boolean canBreakGlass,
        List<String> allowedSchemas,
        List<String> allowedTables,
        List<String> restrictedColumns,
        List<String> deniedColumns,
        List<String> deniedSchemas,
        List<String> deniedTables,
        List<QueryShape> deniedShapes,
        Integer rowLimitOverride,
        Instant expiresAt) {
}
