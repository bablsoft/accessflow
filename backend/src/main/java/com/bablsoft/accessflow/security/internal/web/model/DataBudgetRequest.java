package com.bablsoft.accessflow.security.internal.web.model;

import com.bablsoft.accessflow.core.api.DataBudgetBreachAction;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/** Create/update body for a data budget (#942). Updates are total, like create. */
public record DataBudgetRequest(
        @NotBlank(message = "{validation.data_budget.name.required}")
        @Size(max = 120, message = "{validation.data_budget.name.size}")
        String name,
        @Min(value = 1, message = "{validation.data_budget.max_rows.min}")
        Long maxRows,
        @Min(value = 1, message = "{validation.data_budget.max_bytes.min}")
        Long maxBytes,
        @Min(value = 60, message = "{validation.data_budget.window.range}")
        @Max(value = 44_640, message = "{validation.data_budget.window.range}")
        Integer windowMinutes,
        DataBudgetBreachAction breachAction,
        @Min(value = 1, message = "{validation.data_budget.threshold.range}")
        @Max(value = 99, message = "{validation.data_budget.threshold.range}")
        Integer warnThresholdPercent,
        List<String> appliesToRoles,
        List<UUID> appliesToGroupIds,
        List<UUID> appliesToUserIds,
        Boolean enabled
) {}
