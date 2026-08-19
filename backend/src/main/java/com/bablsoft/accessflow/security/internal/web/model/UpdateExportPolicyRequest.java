package com.bablsoft.accessflow.security.internal.web.model;

import com.bablsoft.accessflow.core.api.DataClassification;
import com.bablsoft.accessflow.core.api.ExportPolicyMode;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

public record UpdateExportPolicyRequest(
        @NotNull(message = "{validation.export_policy_mode.required}")
        ExportPolicyMode mode,
        @Min(value = 1, message = "{validation.export_policy_row_cap.range}")
        @Max(value = 1_000_000, message = "{validation.export_policy_row_cap.range}")
        Integer rowCap,
        List<DataClassification> denyClassifications,
        List<String> appliesToRoles,
        List<UUID> appliesToGroupIds,
        List<UUID> appliesToUserIds,
        Boolean enabled
) {}
