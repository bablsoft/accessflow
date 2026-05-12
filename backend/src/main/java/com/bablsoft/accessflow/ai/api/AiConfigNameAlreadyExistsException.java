package com.bablsoft.accessflow.ai.api;

/**
 * Thrown when another {@code ai_config} row in the same organization already uses the requested
 * name (case-insensitive). Resolved by the global handler to HTTP 409.
 */
public class AiConfigNameAlreadyExistsException extends RuntimeException {

    private final String name;

    public AiConfigNameAlreadyExistsException(String name) {
        super("AI config name already exists: " + name);
        this.name = name;
    }

    public String name() {
        return name;
    }
}
