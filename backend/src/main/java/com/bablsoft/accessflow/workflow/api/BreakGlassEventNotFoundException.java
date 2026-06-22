package com.bablsoft.accessflow.workflow.api;

import java.util.UUID;

/**
 * Thrown when a break-glass event is not found in the caller's organization (AF-385).
 */
public final class BreakGlassEventNotFoundException extends RuntimeException {

    private final UUID eventId;

    public BreakGlassEventNotFoundException(UUID eventId) {
        super("Break-glass event not found: " + eventId);
        this.eventId = eventId;
    }

    public UUID eventId() {
        return eventId;
    }
}
