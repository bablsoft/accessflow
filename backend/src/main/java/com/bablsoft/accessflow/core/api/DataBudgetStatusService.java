package com.bablsoft.accessflow.core.api;

import java.util.List;
import java.util.UUID;

/**
 * Reads a user's data-budget standing (#942) from the append-only usage ledger — a trailing-window
 * sum per budget, no counter table and no reset job.
 */
public interface DataBudgetStatusService {

    /** The user's standing on one datasource; {@link DataBudgetStatus#isEmpty()} when unbudgeted. */
    DataBudgetStatus statusFor(UUID datasourceId, UUID userId);

    /** The user's standing on every datasource of the organization where a budget applies. */
    List<DataBudgetStatus> statusesForUser(UUID organizationId, UUID userId);
}
