package com.partqam.accessflow.ai.api;

import java.util.UUID;

/**
 * Thrown when an {@code ai_config} row does not exist for the requested id (or exists in another
 * organization). Resolved by the global handler to HTTP 404.
 */
public class AiConfigNotFoundException extends RuntimeException {

    private final UUID id;

    public AiConfigNotFoundException(UUID id) {
        super("AI config not found: " + id);
        this.id = id;
    }

    public UUID id() {
        return id;
    }
}
