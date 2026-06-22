package com.bablsoft.accessflow.ai.api;

import java.util.UUID;

/**
 * Thrown when an anomaly status transition is not allowed (e.g. acknowledging an already-dismissed
 * anomaly, or re-acknowledging one). Resolved by the global handler to HTTP 409.
 */
public class IllegalAnomalyStatusTransitionException extends RuntimeException {

    private final UUID id;
    private final BehaviorAnomalyStatus from;
    private final BehaviorAnomalyStatus to;

    public IllegalAnomalyStatusTransitionException(UUID id, BehaviorAnomalyStatus from,
                                                   BehaviorAnomalyStatus to) {
        super("Illegal anomaly status transition for " + id + ": " + from + " -> " + to);
        this.id = id;
        this.from = from;
        this.to = to;
    }

    public UUID id() {
        return id;
    }

    public BehaviorAnomalyStatus from() {
        return from;
    }

    public BehaviorAnomalyStatus to() {
        return to;
    }
}
