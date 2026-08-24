package com.bablsoft.accessflow.deploygov.api;

/** Lifecycle of a rollback follow-up review (#693) — maps to the {@code deployment_rollback_review_status} PG enum. */
public enum DeploymentRollbackReviewStatus {
    PENDING_REVIEW,
    REVIEWED
}
