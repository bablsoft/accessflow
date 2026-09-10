package com.bablsoft.accessflow.core.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One unexpired grant that feeds a user's effective permission on a datasource, before the merge
 * (issue AF-859).
 *
 * <p>{@link DatasourceUserPermissionView} is the merged answer and deliberately keeps no record of
 * where its values came from, which makes it useless for answering "why do I have this". This is the
 * same data one level earlier, so a caller can name the contributing grants without re-deriving the
 * merge — the merge itself runs over exactly these records, so there is only ever one of it.
 *
 * @param sourceId  the {@code datasource_user_permissions} or {@code datasource_group_permissions}
 *                  row id
 * @param groupId   the granting group, or {@code null} for a direct grant
 * @param expiresAt {@code null} means this contribution never expires
 */
public record DatasourcePermissionContribution(
        DatasourcePermissionSourceKind sourceKind,
        UUID sourceId,
        UUID userId,
        UUID datasourceId,
        UUID groupId,
        String groupName,
        boolean canRead,
        boolean canWrite,
        boolean canDdl,
        boolean canBreakGlass,
        List<String> allowedSchemas,
        List<String> allowedTables,
        List<String> restrictedColumns,
        Instant expiresAt) {

    public DatasourcePermissionContribution {
        allowedSchemas = allowedSchemas == null ? List.of() : List.copyOf(allowedSchemas);
        allowedTables = allowedTables == null ? List.of() : List.copyOf(allowedTables);
        restrictedColumns = restrictedColumns == null ? List.of() : List.copyOf(restrictedColumns);
    }
}
