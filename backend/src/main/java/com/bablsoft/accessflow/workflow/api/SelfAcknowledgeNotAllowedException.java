package com.bablsoft.accessflow.workflow.api;

import java.util.UUID;

/**
 * Thrown when the submitter of a break-glass query attempts to acknowledge their own retro-review
 * (AF-385). Mirrors the invariant that a user can never approve their own query — the retrospective
 * reconciliation must be performed by a different admin.
 */
public final class SelfAcknowledgeNotAllowedException extends RuntimeException {

    private final UUID eventId;

    public SelfAcknowledgeNotAllowedException(UUID eventId) {
        super("Submitter cannot acknowledge their own break-glass event: " + eventId);
        this.eventId = eventId;
    }

    public UUID eventId() {
        return eventId;
    }
}
