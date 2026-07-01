package com.bablsoft.accessflow.lifecycle.internal;

import com.bablsoft.accessflow.lifecycle.api.ErasureStatus;

import java.util.UUID;

/**
 * Outcome of recording an erasure-request decision: the persisted (or already-existing, on
 * idempotent replay) decision id and the resulting request status.
 */
record RecordErasureDecisionResult(UUID decisionId, ErasureStatus resultingStatus,
                                   boolean wasIdempotentReplay) {
}
