package com.bablsoft.accessflow.core.events;

import com.bablsoft.accessflow.core.api.DataBudgetBreachAction;

import java.util.UUID;

/**
 * A recorded read carried a user across one of their data budgets' marks (#942): its warn
 * threshold ({@code exhausted=false}) or its limit ({@code exhausted=true}). Published once per
 * crossing — a read that stays above a mark never re-publishes, and a read that jumps straight past
 * the limit publishes only the exhausted crossing.
 */
public record DataBudgetThresholdCrossedEvent(
        UUID organizationId,
        UUID userId,
        UUID datasourceId,
        UUID budgetId,
        String budgetName,
        boolean exhausted,
        Integer warnThresholdPercent,
        int usedPercent,
        Long maxRows,
        Long maxBytes,
        long usedRows,
        long usedBytes,
        int windowMinutes,
        DataBudgetBreachAction breachAction) {
}
