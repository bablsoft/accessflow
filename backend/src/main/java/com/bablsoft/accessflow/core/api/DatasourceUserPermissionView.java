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
        Long bytesScannedLimitOverride,
        Instant expiresAt) {

    /** Backward-compatible constructor without the #941 bytes-scanned cap override. */
    public DatasourceUserPermissionView(UUID id, UUID userId, UUID datasourceId, boolean canRead,
                                        boolean canWrite, boolean canDdl, boolean canBreakGlass,
                                        List<String> allowedSchemas, List<String> allowedTables,
                                        List<String> restrictedColumns, List<String> deniedColumns,
                                        List<String> deniedSchemas, List<String> deniedTables,
                                        List<QueryShape> deniedShapes, Integer rowLimitOverride,
                                        Instant expiresAt) {
        this(id, userId, datasourceId, canRead, canWrite, canDdl, canBreakGlass, allowedSchemas,
                allowedTables, restrictedColumns, deniedColumns, deniedSchemas, deniedTables,
                deniedShapes, rowLimitOverride, null, expiresAt);
    }
}
