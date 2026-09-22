package com.bablsoft.accessflow.schemachange.api;

import java.util.UUID;

/** A promotion does not exist in the caller's organization. Mapped to HTTP 404. */
public final class SchemaChangePromotionNotFoundException extends SchemaChangeException {

    private final UUID promotionId;

    public SchemaChangePromotionNotFoundException(UUID promotionId) {
        super("Schema change promotion not found: " + promotionId);
        this.promotionId = promotionId;
    }

    public UUID promotionId() {
        return promotionId;
    }
}
