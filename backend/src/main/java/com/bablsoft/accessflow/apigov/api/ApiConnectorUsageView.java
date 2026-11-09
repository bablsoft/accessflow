package com.bablsoft.accessflow.apigov.api;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * A connector's usage over a date range (#973). {@code monthToDateSpendMicros} and
 * {@code budgetUsedPercent} are connector-wide — the budget is — while {@code days} is scoped to
 * the caller's own rows for a non-admin. {@code budgetUsedPercent} is null when the connector has
 * no budget or no price.
 */
public record ApiConnectorUsageView(UUID connectorId, LocalDate monthStart, long monthToDateSpendMicros,
                                    Long monthlyBudgetMicros, Integer budgetUsedPercent,
                                    Long costPerCallMicros, List<ApiConnectorUsageDayView> days) {
}
