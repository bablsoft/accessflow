package com.bablsoft.accessflow.core.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ExportPolicyView(
        UUID id,
        UUID datasourceId,
        ExportPolicyMode mode,
        Integer rowCap,
        List<DataClassification> denyClassifications,
        List<String> appliesToRoles,
        List<UUID> appliesToGroupIds,
        List<UUID> appliesToUserIds,
        boolean enabled,
        Instant createdAt,
        Instant updatedAt) {

    public ExportPolicyView {
        denyClassifications = denyClassifications == null ? List.of()
                : List.copyOf(denyClassifications);
        appliesToRoles = appliesToRoles == null ? List.of() : List.copyOf(appliesToRoles);
        appliesToGroupIds = appliesToGroupIds == null ? List.of() : List.copyOf(appliesToGroupIds);
        appliesToUserIds = appliesToUserIds == null ? List.of() : List.copyOf(appliesToUserIds);
    }
}
