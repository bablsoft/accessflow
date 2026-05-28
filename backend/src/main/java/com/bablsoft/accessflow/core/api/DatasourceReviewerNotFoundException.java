package com.bablsoft.accessflow.core.api;

import java.util.UUID;

public class DatasourceReviewerNotFoundException extends RuntimeException {

    private final UUID reviewerId;

    public DatasourceReviewerNotFoundException(UUID reviewerId) {
        super("Datasource reviewer not found: " + reviewerId);
        this.reviewerId = reviewerId;
    }

    public UUID reviewerId() {
        return reviewerId;
    }
}
