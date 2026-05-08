package com.partqam.accessflow.core.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record CreatePermissionCommand(
        UUID userId,
        Boolean canRead,
        Boolean canWrite,
        Boolean canDdl,
        Integer rowLimitOverride,
        List<String> allowedSchemas,
        List<String> allowedTables,
        List<String> restrictedColumns,
        Instant expiresAt
) {}
