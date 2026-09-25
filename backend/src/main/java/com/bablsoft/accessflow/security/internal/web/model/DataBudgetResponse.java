package com.bablsoft.accessflow.security.internal.web.model;

import com.bablsoft.accessflow.core.api.DataBudgetBreachAction;
import com.bablsoft.accessflow.core.api.DataBudgetView;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record DataBudgetResponse(
        UUID id,
        UUID datasourceId,
        String name,
        Long maxRows,
        Long maxBytes,
        int windowMinutes,
        DataBudgetBreachAction breachAction,
        Integer warnThresholdPercent,
        List<String> appliesToRoles,
        List<UUID> appliesToGroupIds,
        List<UUID> appliesToUserIds,
        boolean enabled,
        Instant createdAt,
        Instant updatedAt) {

    public static DataBudgetResponse from(DataBudgetView view) {
        return new DataBudgetResponse(
                view.id(),
                view.datasourceId(),
                view.name(),
                view.maxRows(),
                view.maxBytes(),
                view.windowMinutes(),
                view.breachAction(),
                view.warnThresholdPercent(),
                view.appliesToRoles(),
                view.appliesToGroupIds(),
                view.appliesToUserIds(),
                view.enabled(),
                view.createdAt(),
                view.updatedAt());
    }
}
