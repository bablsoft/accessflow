package com.bablsoft.accessflow.audit.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Filter for {@link AuditLogService#query}. All fields are optional; null means
 * "no filter on this field".
 */
public record AuditLogQuery(
        UUID actorId,
        AuditAction action,
        AuditResourceType resourceType,
        UUID resourceId,
        Instant from,
        Instant to) {

    public static AuditLogQuery empty() {
        return new AuditLogQuery(null, null, null, null, null, null);
    }
}
