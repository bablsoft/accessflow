package com.bablsoft.accessflow.security.internal.web.model;

import com.bablsoft.accessflow.core.api.DataClassification;
import com.bablsoft.accessflow.core.api.ExportPolicyMode;
import com.bablsoft.accessflow.core.api.ExportPolicyView;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ExportPolicyResponse(
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

    public static ExportPolicyResponse from(ExportPolicyView view) {
        return new ExportPolicyResponse(
                view.id(),
                view.datasourceId(),
                view.mode(),
                view.rowCap(),
                view.denyClassifications(),
                view.appliesToRoles(),
                view.appliesToGroupIds(),
                view.appliesToUserIds(),
                view.enabled(),
                view.createdAt(),
                view.updatedAt());
    }
}
