package com.bablsoft.accessflow.workflow.api;

/**
 * Thrown when a routing policy is malformed — an invalid condition tree, an action / parameter
 * mismatch (e.g. {@code REQUIRE_APPROVALS} without a positive approval count), or undecodable
 * stored JSON. Mapped to HTTP 422.
 */
public final class IllegalRoutingPolicyException extends RuntimeException {

    public IllegalRoutingPolicyException(String message) {
        super(message);
    }

    public IllegalRoutingPolicyException(String message, Throwable cause) {
        super(message, cause);
    }
}
