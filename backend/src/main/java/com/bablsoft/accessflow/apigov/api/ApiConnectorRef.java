package com.bablsoft.accessflow.apigov.api;

import java.util.UUID;

/**
 * Lightweight cross-module reference to an API connector — no connection or auth details.
 * {@code reviewPlanId} is nullable (connector without an attached review plan).
 */
public record ApiConnectorRef(UUID id, String name, ApiProtocol protocol, UUID reviewPlanId) {
}
