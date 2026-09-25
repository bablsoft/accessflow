package com.bablsoft.accessflow.core.api;

/**
 * What happens to a SELECT once a data budget (#942) is exhausted. {@link #REJECT} beats
 * {@link #REQUIRE_REVIEW} when several exhausted budgets disagree.
 */
public enum DataBudgetBreachAction {
    REJECT,
    REQUIRE_REVIEW;

    public static DataBudgetBreachAction strictest(DataBudgetBreachAction a, DataBudgetBreachAction b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a == REJECT || b == REJECT ? REJECT : REQUIRE_REVIEW;
    }
}
