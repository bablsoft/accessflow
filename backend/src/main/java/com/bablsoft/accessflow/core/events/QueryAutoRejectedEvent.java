package com.bablsoft.accessflow.core.events;

import java.util.UUID;

/**
 * Published when a query is auto-rejected by the state machine because a routing policy matched with
 * the {@code AUTO_REJECT} action. Carries the matched {@code routing_policy.id} and the reason so the
 * audit and notification listeners can record provenance.
 */
public record QueryAutoRejectedEvent(UUID queryRequestId, UUID matchedPolicyId, String reason) {
}
