package com.bablsoft.accessflow.core.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record CreateDatasourceGroupPermissionCommand(
        UUID groupId,
        Boolean canRead,
        Boolean canWrite,
        Boolean canDdl,
        Boolean canBreakGlass,
        Integer rowLimitOverride,
        List<String> allowedSchemas,
        List<String> allowedTables,
        List<String> restrictedColumns,
        Instant expiresAt) {
}
