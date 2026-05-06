package com.partqam.accessflow.audit.api;

import java.util.Map;
import java.util.UUID;

/**
 * Input to {@link AuditLogService#record(AuditEntry)}. {@code actorId} is null for system-driven
 * rows; {@code ipAddress} / {@code userAgent} are null when the call originates outside an HTTP
 * request thread (e.g. async event listeners).
 */
public record AuditEntry(
        AuditAction action,
        AuditResourceType resourceType,
        UUID resourceId,
        UUID organizationId,
        UUID actorId,
        Map<String, Object> metadata,
        String ipAddress,
        String userAgent) {

    public AuditEntry {
        if (action == null) {
            throw new IllegalArgumentException("action is required");
        }
        if (resourceType == null) {
            throw new IllegalArgumentException("resourceType is required");
        }
        if (organizationId == null) {
            throw new IllegalArgumentException("organizationId is required");
        }
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
