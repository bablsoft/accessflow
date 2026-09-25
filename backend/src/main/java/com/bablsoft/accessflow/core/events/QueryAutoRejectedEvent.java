package com.bablsoft.accessflow.core.events;

import java.util.UUID;

/**
 * Published when a query is rejected without a reviewer: a routing policy matched with the
 * {@code AUTO_REJECT} action (carrying its {@code routing_policy.id}), the bytes-scanned cap refused
 * it (#941), or an external ticket resolution rejected it — the last two with a null policy id. The
 * reason lets the audit and notification listeners record provenance.
 */
public record QueryAutoRejectedEvent(UUID queryRequestId, UUID matchedPolicyId, String reason) {
}
