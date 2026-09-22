package com.bablsoft.accessflow.schemachange.api;

import java.util.UUID;

/**
 * The promotion is already terminal, or its request group can no longer be cancelled — approved
 * for an immediate run, executing, or finished (#880). Mapped to HTTP 409.
 */
public final class SchemaChangePromotionNotCancellableException extends SchemaChangeException {

    private final UUID promotionId;
    private final SchemaChangePromotionStatus currentStatus;

    public SchemaChangePromotionNotCancellableException(UUID promotionId, SchemaChangePromotionStatus currentStatus) {
        super("Schema change promotion " + promotionId + " cannot be cancelled in status " + currentStatus);
        this.promotionId = promotionId;
        this.currentStatus = currentStatus;
    }

    public UUID promotionId() {
        return promotionId;
    }

    public SchemaChangePromotionStatus currentStatus() {
        return currentStatus;
    }
}
