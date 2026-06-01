package com.bablsoft.accessflow.access.internal;

import com.bablsoft.accessflow.access.api.AccessGrantStatus;

import java.util.UUID;

/**
 * Outcome of recording an access-request decision: the persisted (or already-existing, on
 * idempotent replay) decision id and the resulting request status.
 */
record RecordAccessDecisionResult(UUID decisionId, AccessGrantStatus resultingStatus,
                                  boolean wasIdempotentReplay) {
}
