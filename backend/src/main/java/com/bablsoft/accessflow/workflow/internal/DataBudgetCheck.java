package com.bablsoft.accessflow.workflow.internal;

import com.bablsoft.accessflow.core.api.DataBudgetBreachAction;
import com.bablsoft.accessflow.core.api.DataBudgetConsumption;
import com.bablsoft.accessflow.core.api.DataBudgetStatus;

/**
 * The submitter's data-volume budget standing (#942) for one SELECT — the input
 * {@link QueryDecisionEvaluator} decides on, like the bytes-scanned cap.
 *
 * @param deciding    the exhausted budget whose action applies, {@code null} while allowance remains
 * @param action      the strictest action among exhausted budgets, {@code null} while none is
 * @param usedPercent the highest share of any applying budget used, 0–100+
 */
record DataBudgetCheck(DataBudgetConsumption deciding, DataBudgetBreachAction action,
                       double usedPercent, Long remainingRows, Long remainingBytes) {

    /** {@code null} when no budget applies, so "no budget" and "within budget" stay distinct. */
    static DataBudgetCheck of(DataBudgetStatus status) {
        if (status == null || status.isEmpty()) {
            return null;
        }
        var used = status.usedPercent();
        return new DataBudgetCheck(status.decidingBudget(), status.breachAction(),
                used == null ? 0d : used, status.remainingRows(), status.remainingBytes());
    }

    boolean exhausted() {
        return action != null;
    }

    boolean rejects() {
        return action == DataBudgetBreachAction.REJECT;
    }

    boolean forcesReview() {
        return action == DataBudgetBreachAction.REQUIRE_REVIEW;
    }
}
