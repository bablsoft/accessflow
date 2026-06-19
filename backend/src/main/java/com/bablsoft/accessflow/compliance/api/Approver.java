package com.bablsoft.accessflow.compliance.api;

import java.time.Instant;

/**
 * A reviewer who approved a query, captured from the immutable review-decision snapshot as it stood
 * at execution time (forensically correct, independent of later user edits/deletions).
 */
public record Approver(String email, String displayName, String decision, Instant decidedAt) {
}
