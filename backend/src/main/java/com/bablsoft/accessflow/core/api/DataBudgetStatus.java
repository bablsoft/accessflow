package com.bablsoft.accessflow.core.api;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A user's effective data-budget standing on one datasource (#942): every enabled budget that
 * applies to them, each over its own window. The effective view is the most constrained budget —
 * the smallest remaining allowance per metric, exhausted when any budget is, and the strictest
 * breach action among the exhausted ones. An empty status means no budget applies.
 */
public record DataBudgetStatus(UUID datasourceId, String datasourceName,
                               List<DataBudgetConsumption> budgets) {

    public DataBudgetStatus {
        budgets = budgets == null ? List.of() : List.copyOf(budgets);
    }

    public static DataBudgetStatus none(UUID datasourceId) {
        return new DataBudgetStatus(datasourceId, null, List.of());
    }

    public boolean isEmpty() {
        return budgets.isEmpty();
    }

    public boolean exhausted() {
        return budgets.stream().anyMatch(DataBudgetConsumption::exhausted);
    }

    /** The strictest action among exhausted budgets; null while none is exhausted. */
    public DataBudgetBreachAction breachAction() {
        DataBudgetBreachAction action = null;
        for (var budget : budgets) {
            if (budget.exhausted()) {
                action = DataBudgetBreachAction.strictest(action, budget.breachAction());
            }
        }
        return action;
    }

    /** The exhausted budget that decides {@link #breachAction()}; null while none is exhausted. */
    public DataBudgetConsumption decidingBudget() {
        var action = breachAction();
        if (action == null) {
            return null;
        }
        return budgets.stream()
                .filter(b -> b.exhausted() && b.breachAction() == action)
                .findFirst()
                .orElse(null);
    }

    /** Smallest remaining row allowance across budgets bounding rows; null when none does. */
    public Long remainingRows() {
        return budgets.stream().map(DataBudgetConsumption::remainingRows).filter(Objects::nonNull)
                .min(Long::compare).orElse(null);
    }

    /** Smallest remaining byte allowance across budgets bounding bytes; null when none does. */
    public Long remainingBytes() {
        return budgets.stream().map(DataBudgetConsumption::remainingBytes).filter(Objects::nonNull)
                .min(Long::compare).orElse(null);
    }

    /** The highest percentage used across budgets; null when no budget applies. */
    public Double usedPercent() {
        return budgets.stream().map(DataBudgetConsumption::usedPercent)
                .max(Double::compare).orElse(null);
    }
}
