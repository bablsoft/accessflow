package com.bablsoft.accessflow.workflow.api;

import java.util.UUID;

/**
 * Thrown when an admin attempts to acknowledge a break-glass event that has already been reviewed
 * (AF-385).
 */
public final class BreakGlassAlreadyReviewedException extends RuntimeException {

    private final UUID eventId;

    public BreakGlassAlreadyReviewedException(UUID eventId) {
        super("Break-glass event already reviewed: " + eventId);
        this.eventId = eventId;
    }

    public UUID eventId() {
        return eventId;
    }
}
