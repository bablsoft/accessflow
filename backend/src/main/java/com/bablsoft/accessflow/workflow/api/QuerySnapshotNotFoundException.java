package com.bablsoft.accessflow.workflow.api;

import java.util.UUID;

/**
 * Thrown when a replay is requested for a query that has no execution snapshot — i.e. it never reached
 * {@code EXECUTED}, or it lives in another organization. Maps to HTTP 404.
 */
public final class QuerySnapshotNotFoundException extends RuntimeException {

    private final UUID queryRequestId;

    public QuerySnapshotNotFoundException(UUID queryRequestId) {
        super("No execution snapshot exists for query " + queryRequestId);
        this.queryRequestId = queryRequestId;
    }

    public UUID queryRequestId() {
        return queryRequestId;
    }
}
