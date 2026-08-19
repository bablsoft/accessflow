package com.bablsoft.accessflow.core.api;

import java.util.List;
import java.util.UUID;

public record UpdateExportPolicyCommand(
        ExportPolicyMode mode,
        Integer rowCap,
        List<DataClassification> denyClassifications,
        List<String> appliesToRoles,
        List<UUID> appliesToGroupIds,
        List<UUID> appliesToUserIds,
        Boolean enabled) {
}
