package com.partqam.accessflow.core.api;

import java.util.UUID;

/**
 * Outcome of recording a review decision: the persisted (or already-existing, on idempotent
 * replay) decision id and the resulting query status.
 */
public record RecordDecisionResult(UUID decisionId, QueryStatus resultingStatus, boolean wasIdempotentReplay) {
}
