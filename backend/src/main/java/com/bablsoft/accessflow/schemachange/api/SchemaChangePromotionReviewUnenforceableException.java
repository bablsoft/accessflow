package com.bablsoft.accessflow.schemachange.api;

import java.util.UUID;

/**
 * The target environment requires review but the request-group path can only enforce the review
 * plan attached to the target datasource — and that plan is absent or does not require human
 * approval, so the promotion would auto-approve (#880). Refused rather than silently weakened.
 * Mapped to HTTP 422.
 */
public final class SchemaChangePromotionReviewUnenforceableException extends SchemaChangeException {

    private final UUID environmentId;
    private final UUID datasourceId;

    public SchemaChangePromotionReviewUnenforceableException(UUID environmentId, UUID datasourceId) {
        super("Environment " + environmentId + " requires review but datasource " + datasourceId
                + " has no review plan requiring human approval");
        this.environmentId = environmentId;
        this.datasourceId = datasourceId;
    }

    public UUID environmentId() {
        return environmentId;
    }

    public UUID datasourceId() {
        return datasourceId;
    }
}
