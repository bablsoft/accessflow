package com.bablsoft.accessflow.audit.api;

import com.bablsoft.accessflow.core.api.GrantResourceKind;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One successful use of a standing grant, projected from {@code audit_log} (#625). Derived from
 * audit <strong>metadata</strong> only — never query result data or response bodies.
 *
 * <p>{@code targets} are the parts of the grant's scope the call actually exercised: referenced
 * tables for a {@code DATASOURCE} event, the invoked operation for an {@code API_CONNECTOR} one. It
 * is empty — never null — for rows written before the corresponding metadata enrichment landed,
 * which callers must read as "used, scope unknown" rather than "used nothing".
 *
 * <p>{@code auditLogId} is the source row's id, and it is what makes exact resumption possible:
 * {@code created_at} alone is not unique, so a cursor that stores only a timestamp either re-serves
 * or skips the events sharing its final instant.
 */
public record GrantUsageAuditEvent(
        UUID auditLogId,
        UUID organizationId,
        UUID userId,
        GrantResourceKind resourceKind,
        UUID resourceId,
        Instant occurredAt,
        List<String> targets) {

    public GrantUsageAuditEvent {
        targets = targets == null ? List.of() : List.copyOf(targets);
    }
}
