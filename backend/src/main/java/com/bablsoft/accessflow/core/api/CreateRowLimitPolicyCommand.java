package com.bablsoft.accessflow.core.api;

import java.util.List;
import java.util.UUID;

public record CreateRowLimitPolicyCommand(
        String schemaName,
        String tableName,
        Integer maxRows,
        List<String> appliesToRoles,
        List<UUID> appliesToGroupIds,
        List<UUID> appliesToUserIds,
        Boolean enabled) {
}
