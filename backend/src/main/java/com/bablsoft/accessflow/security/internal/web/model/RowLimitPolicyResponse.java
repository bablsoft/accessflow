package com.bablsoft.accessflow.security.internal.web.model;

import com.bablsoft.accessflow.core.api.RowLimitPolicyView;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record RowLimitPolicyResponse(
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

    public static RowLimitPolicyResponse from(RowLimitPolicyView view) {
        return new RowLimitPolicyResponse(
                view.id(),
                view.datasourceId(),
                view.schemaName(),
                view.tableName(),
                view.maxRows(),
                view.appliesToRoles(),
                view.appliesToGroupIds(),
                view.appliesToUserIds(),
                view.enabled(),
                view.createdAt(),
                view.updatedAt());
    }
}
