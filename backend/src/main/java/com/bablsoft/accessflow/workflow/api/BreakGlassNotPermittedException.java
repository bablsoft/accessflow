package com.bablsoft.accessflow.workflow.api;

import java.util.UUID;

/**
 * Thrown when a caller attempts a break-glass execution without an effective {@code can_break_glass}
 * grant on the datasource (missing, expired, or lacking the capability/allow-list for the query) —
 * AF-385. The grant is required for everyone, including admins.
 */
public final class BreakGlassNotPermittedException extends RuntimeException {

    private final UUID datasourceId;

    public BreakGlassNotPermittedException(UUID datasourceId) {
        super("Break-glass is not permitted on datasource " + datasourceId);
        this.datasourceId = datasourceId;
    }

    public UUID datasourceId() {
        return datasourceId;
    }
}
