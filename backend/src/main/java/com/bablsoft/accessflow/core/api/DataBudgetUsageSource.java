package com.bablsoft.accessflow.core.api;

/** Which read path delivered the rows a data-budget ledger entry (#942) counts. */
public enum DataBudgetUsageSource {
    QUERY,
    REQUEST_GROUP,
    SAMPLE_DATA
}
