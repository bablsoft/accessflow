package com.bablsoft.accessflow.core.api;

import java.util.List;
import java.util.UUID;

public record UpdateRowSecurityPolicyCommand(
        String tableName,
        String columnName,
        RowSecurityOperator operator,
        RowSecurityValueType valueType,
        String valueExpression,
        List<String> appliesToRoles,
        List<UUID> appliesToGroupIds,
        List<UUID> appliesToUserIds,
        Boolean enabled) {
}
