package com.bablsoft.accessflow.security.internal.web.model;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

public record CreateRowLimitPolicyRequest(
        @Size(max = 255, message = "{validation.row_limit_schema.size}")
        String schemaName,
        @NotBlank(message = "{validation.row_limit_table.required}")
        @Size(max = 255, message = "{validation.row_limit_table.size}")
        String tableName,
        @NotNull(message = "{validation.row_limit_max_rows.required}")
        @Min(value = 1, message = "{validation.row_limit_max_rows.range}")
        @Max(value = 1_000_000, message = "{validation.row_limit_max_rows.range}")
        Integer maxRows,
        List<String> appliesToRoles,
        List<UUID> appliesToGroupIds,
        List<UUID> appliesToUserIds,
        Boolean enabled
) {}
