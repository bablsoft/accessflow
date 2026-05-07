package com.partqam.accessflow.audit.internal.web;

import com.partqam.accessflow.audit.api.AuditAction;
import com.partqam.accessflow.audit.api.AuditLogView;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record AuditLogResponse(
        UUID id,
        UUID organizationId,
        UUID actorId,
        String actorEmail,
        String actorDisplayName,
        AuditAction action,
        String resourceType,
        UUID resourceId,
        Map<String, Object> metadata,
        String ipAddress,
        String userAgent,
        Instant createdAt
) {
    public static AuditLogResponse from(AuditLogView view, String actorEmail, String actorDisplayName) {
        return new AuditLogResponse(
                view.id(),
                view.organizationId(),
                view.actorId(),
                actorEmail,
                actorDisplayName,
                view.action(),
                view.resourceType().dbValue(),
                view.resourceId(),
                view.metadata(),
                view.ipAddress(),
                view.userAgent(),
                view.createdAt());
    }
}
