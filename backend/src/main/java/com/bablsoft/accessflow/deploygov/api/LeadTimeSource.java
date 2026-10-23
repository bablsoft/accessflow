package com.bablsoft.accessflow.deploygov.api;

/**
 * Where the lead-time samples started (#984): the {@code commit_timestamp} metadata key on every
 * sample, the {@code created_at} fallback on every sample, or a mix of both.
 */
public enum LeadTimeSource {
    COMMIT_TIMESTAMP,
    SUBMITTED_AT,
    MIXED
}
