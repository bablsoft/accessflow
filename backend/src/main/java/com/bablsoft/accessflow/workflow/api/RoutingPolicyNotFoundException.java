package com.bablsoft.accessflow.workflow.api;

import java.util.UUID;

/**
 * Thrown when no routing policy matches the given id within the caller's organization. Mapped to
 * HTTP 404.
 */
public final class RoutingPolicyNotFoundException extends RuntimeException {

    private final UUID policyId;

    public RoutingPolicyNotFoundException(UUID policyId) {
        super("Routing policy not found: " + policyId);
        this.policyId = policyId;
    }

    public UUID policyId() {
        return policyId;
    }
}
