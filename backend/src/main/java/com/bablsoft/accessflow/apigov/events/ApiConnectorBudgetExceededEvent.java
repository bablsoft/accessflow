package com.bablsoft.accessflow.apigov.events;

import java.util.UUID;

/**
 * Published at most once per connector per UTC month, when month-to-date spend first reaches the
 * connector's monthly budget (#973). Consumed by notifications, which fans out to org admins.
 */
public record ApiConnectorBudgetExceededEvent(UUID connectorId, UUID organizationId,
                                              long spendMicros, long budgetMicros) {
}
