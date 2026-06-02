package com.bablsoft.accessflow.workflow.api;

/**
 * Thrown when a create / update / reorder would violate the {@code (organization_id, priority)}
 * uniqueness constraint. Mapped to HTTP 409 — priorities must be unique per organization so the
 * first-match-by-priority scan is deterministic.
 */
public final class RoutingPolicyPriorityConflictException extends RuntimeException {

    private final int priority;

    public RoutingPolicyPriorityConflictException(int priority) {
        super("Routing policy priority already in use: " + priority);
        this.priority = priority;
    }

    public int priority() {
        return priority;
    }
}
