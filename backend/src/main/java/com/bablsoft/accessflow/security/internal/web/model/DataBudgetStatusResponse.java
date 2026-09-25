package com.bablsoft.accessflow.security.internal.web.model;

import com.bablsoft.accessflow.core.api.DataBudgetBreachAction;
import com.bablsoft.accessflow.core.api.DataBudgetConsumption;
import com.bablsoft.accessflow.core.api.DataBudgetStatus;

import java.util.List;
import java.util.UUID;

/**
 * A user's data-budget standing on one datasource (#942). {@code budgets} is empty when no budget
 * applies; the top-level fields are then null / false.
 */
public record DataBudgetStatusResponse(
        UUID datasourceId,
        String datasourceName,
        boolean exhausted,
        DataBudgetBreachAction breachAction,
        Long remainingRows,
        Long remainingBytes,
        Integer usedPercent,
        List<Budget> budgets) {

    public record Budget(
            UUID id,
            String name,
            Long maxRows,
            Long maxBytes,
            int windowMinutes,
            DataBudgetBreachAction breachAction,
            Integer warnThresholdPercent,
            long usedRows,
            long usedBytes,
            Long remainingRows,
            Long remainingBytes,
            int usedPercent,
            boolean exhausted) {

        static Budget from(DataBudgetConsumption c) {
            return new Budget(c.budgetId(), c.name(), c.maxRows(), c.maxBytes(), c.windowMinutes(),
                    c.breachAction(), c.warnThresholdPercent(), c.usedRows(), c.usedBytes(),
                    c.remainingRows(), c.remainingBytes(), floor(c.usedPercent()), c.exhausted());
        }
    }

    public static DataBudgetStatusResponse from(DataBudgetStatus status) {
        var used = status.usedPercent();
        return new DataBudgetStatusResponse(
                status.datasourceId(),
                status.datasourceName(),
                status.exhausted(),
                status.breachAction(),
                status.remainingRows(),
                status.remainingBytes(),
                used == null ? null : floor(used),
                status.budgets().stream().map(Budget::from).toList());
    }

    private static int floor(double percent) {
        return (int) Math.min(Integer.MAX_VALUE, Math.floor(percent));
    }
}
