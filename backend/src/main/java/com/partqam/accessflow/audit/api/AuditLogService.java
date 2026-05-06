package com.partqam.accessflow.audit.api;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

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
}
