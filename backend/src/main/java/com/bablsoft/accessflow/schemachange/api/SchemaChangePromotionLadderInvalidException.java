package com.bablsoft.accessflow.schemachange.api;

import java.util.UUID;

/**
 * The pipeline's environments do not carry distinct {@code sort_order} values, so "every
 * lower-ordered environment" is not well defined and the ladder gate refuses rather than passing
 * vacuously (#880). Mapped to HTTP 409.
 */
public final class SchemaChangePromotionLadderInvalidException extends SchemaChangeException {

    private final UUID pipelineId;

    public SchemaChangePromotionLadderInvalidException(UUID pipelineId) {
        super("Pipeline environments do not form a ladder of distinct sort orders: " + pipelineId);
        this.pipelineId = pipelineId;
    }

    public UUID pipelineId() {
        return pipelineId;
    }
}
