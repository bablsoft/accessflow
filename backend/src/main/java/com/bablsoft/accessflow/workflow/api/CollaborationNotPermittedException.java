package com.bablsoft.accessflow.workflow.api;

import java.util.UUID;

/**
 * Thrown when a user may not collaborate on (or comment on) a query — they are neither the
 * submitter, an eligible reviewer, nor an admin, or the query is not in a co-authorable state.
 * Maps to HTTP 403. Deliberately does not distinguish "not authorized" from "wrong state" so a
 * caller cannot probe a query's lifecycle they have no part in.
 */
public final class CollaborationNotPermittedException extends RuntimeException {

    private final UUID queryRequestId;

    public CollaborationNotPermittedException(UUID queryRequestId) {
        super("Collaboration not permitted on query " + queryRequestId);
        this.queryRequestId = queryRequestId;
    }

    public UUID queryRequestId() {
        return queryRequestId;
    }
}
