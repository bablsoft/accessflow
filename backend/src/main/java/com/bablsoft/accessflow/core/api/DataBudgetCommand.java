package com.bablsoft.accessflow.core.api;

import java.util.List;
import java.util.UUID;

/**
 * Create/update payload for a data budget (#942). Updates are total: every field is re-applied.
 * At least one of {@code maxRows} / {@code maxBytes} must be set; {@code windowMinutes} and
 * {@code breachAction} fall back to 1440 and {@link DataBudgetBreachAction#REQUIRE_REVIEW}.
 */
public record DataBudgetCommand(
        String name,
        Long maxRows,
        Long maxBytes,
        Integer windowMinutes,
        DataBudgetBreachAction breachAction,
        Integer warnThresholdPercent,
        List<String> appliesToRoles,
        List<UUID> appliesToGroupIds,
        List<UUID> appliesToUserIds,
        Boolean enabled) {
}
