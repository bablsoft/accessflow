package com.bablsoft.accessflow.workflow.api;

import java.util.UUID;

/** No decision hook with this id in the caller's organization (#945). Mapped to HTTP 404. */
public final class DecisionHookNotFoundException extends RuntimeException {

    private final UUID hookId;

    public DecisionHookNotFoundException(UUID hookId) {
        super("Decision hook not found: " + hookId);
        this.hookId = hookId;
    }

    public UUID hookId() {
        return hookId;
    }
}
