package com.bablsoft.accessflow.core.api;

/**
 * A read was refused because the reader's data budget (#942) is exhausted — at execution under
 * {@link DataBudgetBreachAction#REJECT} (or {@code REQUIRE_REVIEW} without a human approval), and
 * on the sample-data path, which has no review to escalate to. The message is the caller's
 * localized explanation; execution paths record it as the failure reason.
 */
public class DataBudgetExhaustedException extends RuntimeException {

    private final transient DataBudgetConsumption budget;

    public DataBudgetExhaustedException(String message, DataBudgetConsumption budget) {
        super(message);
        this.budget = budget;
    }

    public DataBudgetConsumption budget() {
        return budget;
    }
}
