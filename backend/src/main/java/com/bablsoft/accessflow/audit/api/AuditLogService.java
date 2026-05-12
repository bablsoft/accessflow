package com.bablsoft.accessflow.audit.api;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.UUID;

/**
 * Single entry point for recording and querying audit events. Implementations write append-only
 * rows to {@code audit_log}; reads are always scoped to the caller's organization.
 */
public interface AuditLogService {

    /**
     * Persists a new audit row. Returns the generated row id. Implementations must never throw out
     * of the workflow that triggered the audit — callers may still wrap calls in try/catch as a
     * defensive measure for system-driven listeners that must not propagate failures.
     */
    UUID record(AuditEntry entry);

    /**
     * Reads audit rows scoped to {@code organizationId}, optionally filtered by {@code filter}.
     * Cross-tenant reads are forbidden — the implementation must always AND the org clause.
     */
    Page<AuditLogView> query(UUID organizationId, AuditLogQuery filter, Pageable pageable);

    /**
     * Walks the HMAC-SHA256 hash chain for {@code organizationId} in ASC {@code created_at} order
     * over the optional {@code [from, to)} window and returns the verification outcome. Rows
     * written before V26 keep NULL hashes and are skipped as "pre-chain". The first row with a
     * non-null {@code current_hash} anchors the chain and must have {@code previous_hash IS NULL}.
     */
    AuditLogVerificationResult verify(UUID organizationId, Instant from, Instant to);
}
