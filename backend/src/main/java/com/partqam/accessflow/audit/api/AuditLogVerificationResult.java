package com.partqam.accessflow.audit.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Outcome of an audit-log hash-chain verification run. {@code ok=true} means every chained row
 * in the requested window matched its expected HMAC; {@code rowsChecked} counts only rows that
 * participated in the chain (pre-V26 rows with NULL hashes are skipped).
 *
 * <p>On a failure {@code firstBadRowId} identifies the first row whose chain invariant did not
 * hold, {@code firstBadCreatedAt} is its {@code created_at}, and {@code firstBadReason} is a
 * stable, machine-readable string describing which invariant failed ({@code current_hash_mismatch},
 * {@code previous_hash_mismatch}, {@code anchor_has_previous}, or {@code null_hash_in_chain}).
 */
public record AuditLogVerificationResult(
        boolean ok,
        long rowsChecked,
        UUID firstBadRowId,
        Instant firstBadCreatedAt,
        String firstBadReason) {

    public static AuditLogVerificationResult ok(long rowsChecked) {
        return new AuditLogVerificationResult(true, rowsChecked, null, null, null);
    }

    public static AuditLogVerificationResult fail(long rowsChecked, UUID rowId, Instant createdAt,
                                                  String reason) {
        return new AuditLogVerificationResult(false, rowsChecked, rowId, createdAt, reason);
    }
}
