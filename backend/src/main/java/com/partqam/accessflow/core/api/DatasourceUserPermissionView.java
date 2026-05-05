package com.partqam.accessflow.core.api;

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
        List<String> allowedSchemas,
        List<String> allowedTables,
        Instant expiresAt) {
}
