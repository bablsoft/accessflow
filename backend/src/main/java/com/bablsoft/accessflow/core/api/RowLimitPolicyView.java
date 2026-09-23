package com.bablsoft.accessflow.core.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record RowLimitPolicyView(
        UUID id,
        UUID datasourceId,
        String schemaName,
        String tableName,
        int maxRows,
        List<String> appliesToRoles,
        List<UUID> appliesToGroupIds,
        List<UUID> appliesToUserIds,
        boolean enabled,
        Instant createdAt,
        Instant updatedAt) {

    public RowLimitPolicyView {
        appliesToRoles = appliesToRoles == null ? List.of() : List.copyOf(appliesToRoles);
        appliesToGroupIds = appliesToGroupIds == null ? List.of() : List.copyOf(appliesToGroupIds);
        appliesToUserIds = appliesToUserIds == null ? List.of() : List.copyOf(appliesToUserIds);
    }
}
