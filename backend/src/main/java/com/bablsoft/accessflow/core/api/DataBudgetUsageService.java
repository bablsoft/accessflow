package com.bablsoft.accessflow.core.api;

/**
 * Charges delivered reads to the usage ledger (#942). A read on a datasource where no budget
 * applies to the user writes nothing. Crossing a budget's warn threshold or its limit publishes a
 * {@code core.events.DataBudgetThresholdCrossedEvent}, computed statelessly from the before/after
 * consumption, so no dedup table is needed. Runs in its own transaction: a failure never affects
 * the read that was already delivered, and callers log rather than propagate it.
 */
public interface DataBudgetUsageService {

    void record(DataBudgetUsageRecord usage);
}
