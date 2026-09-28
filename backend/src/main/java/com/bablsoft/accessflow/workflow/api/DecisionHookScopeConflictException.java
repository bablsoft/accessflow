package com.bablsoft.accessflow.workflow.api;

import java.util.UUID;

/**
 * The datasource, or the organization default when {@code datasourceId} is {@code null}, already
 * has a decision hook (#945). Mapped to HTTP 409.
 */
public final class DecisionHookScopeConflictException extends RuntimeException {

    private final UUID datasourceId;

    public DecisionHookScopeConflictException(UUID datasourceId) {
        super("A decision hook already exists for "
                + (datasourceId == null ? "the organization default" : "datasource " + datasourceId));
        this.datasourceId = datasourceId;
    }

    public UUID datasourceId() {
        return datasourceId;
    }
}
