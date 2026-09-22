package com.bablsoft.accessflow.schemachange.api;

import java.util.UUID;

/**
 * A lower-ordered environment with a datasource bound has no {@code APPLIED} promotion of the
 * change set yet — the ladder gate (#880). Names the first blocking rung in ladder order. Mapped
 * to HTTP 409.
 */
public final class SchemaChangePromotionLadderBlockedException extends SchemaChangeException {

    private final UUID changeSetId;
    private final UUID blockingEnvironmentId;
    private final String blockingEnvironmentName;

    public SchemaChangePromotionLadderBlockedException(UUID changeSetId, UUID blockingEnvironmentId,
                                                       String blockingEnvironmentName) {
        super("Schema change set " + changeSetId + " has not been applied to lower environment "
                + blockingEnvironmentName + " (" + blockingEnvironmentId + ")");
        this.changeSetId = changeSetId;
        this.blockingEnvironmentId = blockingEnvironmentId;
        this.blockingEnvironmentName = blockingEnvironmentName;
    }

    public UUID changeSetId() {
        return changeSetId;
    }

    public UUID blockingEnvironmentId() {
        return blockingEnvironmentId;
    }

    public String blockingEnvironmentName() {
        return blockingEnvironmentName;
    }
}
