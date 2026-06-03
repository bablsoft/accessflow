package com.bablsoft.accessflow.security.internal.web.model;

import com.bablsoft.accessflow.core.api.RowSecurityOperator;
import com.bablsoft.accessflow.core.api.RowSecurityPolicyView;
import com.bablsoft.accessflow.core.api.RowSecurityValueType;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record RowSecurityPolicyResponse(
        UUID id,
        UUID datasourceId,
        String tableName,
        String columnName,
        RowSecurityOperator operator,
        RowSecurityValueType valueType,
        String valueExpression,
        List<String> appliesToRoles,
        List<UUID> appliesToGroupIds,
        List<UUID> appliesToUserIds,
        boolean enabled,
        Instant createdAt,
        Instant updatedAt) {

    public static RowSecurityPolicyResponse from(RowSecurityPolicyView view) {
        return new RowSecurityPolicyResponse(
                view.id(),
                view.datasourceId(),
                view.tableName(),
                view.columnName(),
                view.operator(),
                view.valueType(),
                view.valueExpression(),
                view.appliesToRoles(),
                view.appliesToGroupIds(),
                view.appliesToUserIds(),
                view.enabled(),
                view.createdAt(),
                view.updatedAt());
    }
}
