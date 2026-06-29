package com.bablsoft.accessflow.apigov.events;

import java.util.UUID;

/**
 * Published when a connector's outbound OAuth2 token fetch has failed repeatedly (the consecutive
 * failure counter crossed the configured alert threshold) — the connector is effectively down.
 * Consumed by notifications, which fans out to org admins. Carries no token or secret material.
 */
public record ApiConnectorTokenFailureEvent(UUID connectorId, UUID organizationId) {
}
