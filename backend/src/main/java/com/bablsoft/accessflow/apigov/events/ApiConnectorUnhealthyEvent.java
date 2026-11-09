package com.bablsoft.accessflow.apigov.events;

import java.util.UUID;

/**
 * Published once when a connector's scheduled probes cross the consecutive-failure threshold and it
 * flips to {@code UNHEALTHY} (#894) — never on later failing ticks, never on recovery. Carries no
 * credential material; {@code message} is the last probe's (truncated) error.
 */
public record ApiConnectorUnhealthyEvent(UUID connectorId, UUID organizationId, int consecutiveFailures,
                                         String message) {
}
