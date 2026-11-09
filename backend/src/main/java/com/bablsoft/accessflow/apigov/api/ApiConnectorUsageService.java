package com.bablsoft.accessflow.apigov.api;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Spend accounting for API connectors (#973): accrues each governed call into the daily
 * {@code (connector, user, UTC date)} rollup and reads it back. A budget is an accounting signal —
 * nothing here ever blocks an execution; exhaustion surfaces as a routing fact and a once-a-month
 * notification.
 */
public interface ApiConnectorUsageService {

    /**
     * Adds one outcome to the rollup. {@code units} multiplies the price (1 per call today; a
     * per-page accrual passes the page count). Returns the spend accrued, in micros.
     */
    long accrue(UUID connectorId, UUID organizationId, UUID userId, ApiUsageOutcome outcome, int units);

    /** Month-to-date spend as a floored percentage of the budget; null when unbudgeted or unpriced. */
    Integer budgetUsedPercent(UUID connectorId);

    /**
     * Usage between {@code from} and {@code to} (inclusive, UTC; defaults: current month to today).
     * A null {@code userId} reads the whole connector (admin); otherwise the caller must be granted
     * the connector and sees only their own rows. Throws {@link ApiConnectorNotFoundException} when
     * the connector is not visible and {@link IllegalApiUsageRangeException} for a bad range.
     */
    ApiConnectorUsageView usage(UUID connectorId, UUID organizationId, UUID userId,
                                LocalDate from, LocalDate to);
}
