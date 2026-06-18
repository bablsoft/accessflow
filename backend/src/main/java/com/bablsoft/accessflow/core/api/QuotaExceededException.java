package com.bablsoft.accessflow.core.api;

import java.util.UUID;

/**
 * Thrown when an operation would push an organization past one of its per-org quotas (AF-456).
 * Carries the {@link QuotaType}, the configured {@code limit}, and the {@code current} count so the
 * web layer can build a precise, localised error message.
 */
public final class QuotaExceededException extends RuntimeException {

    private final QuotaType quotaType;
    private final UUID organizationId;
    private final int limit;
    private final long current;

    public QuotaExceededException(QuotaType quotaType, UUID organizationId, int limit, long current) {
        super("Quota exceeded for organization " + organizationId + ": " + quotaType
                + " (limit=" + limit + ", current=" + current + ")");
        this.quotaType = quotaType;
        this.organizationId = organizationId;
        this.limit = limit;
        this.current = current;
    }

    public QuotaType quotaType() {
        return quotaType;
    }

    public UUID organizationId() {
        return organizationId;
    }

    public int limit() {
        return limit;
    }

    public long current() {
        return current;
    }
}
