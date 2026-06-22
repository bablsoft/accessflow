package com.bablsoft.accessflow.ai.api;

import java.util.UUID;

/**
 * Thrown when a {@code behavior_anomaly} row does not exist for the requested id (or exists in
 * another organization). Resolved by the global handler to HTTP 404.
 */
public class AnomalyNotFoundException extends RuntimeException {

    private final UUID id;

    public AnomalyNotFoundException(UUID id) {
        super("Behavior anomaly not found: " + id);
        this.id = id;
    }

    public UUID id() {
        return id;
    }
}
