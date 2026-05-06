package com.partqam.accessflow.audit.api;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Read DTO returned by {@link AuditLogService#query}. */
public record AuditLogView(
        UUID id,
        UUID organizationId,
        UUID actorId,
        AuditAction action,
        AuditResourceType resourceType,
        UUID resourceId,
        Map<String, Object> metadata,
        String ipAddress,
        String userAgent,
        Instant createdAt) {
}
