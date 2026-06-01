package com.bablsoft.accessflow.access.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Read view of an access-grant request, enriched with the requester email and datasource name
 * for display. Used for the requester's own list and as the cross-module lookup payload consumed
 * by the notifications, realtime, and audit modules (so they never touch {@code access.internal}).
 */
public record AccessRequestView(
        UUID id,
        UUID organizationId,
        UUID requesterId,
        String requesterEmail,
        UUID datasourceId,
        String datasourceName,
        boolean canRead,
        boolean canWrite,
        boolean canDdl,
        List<String> allowedSchemas,
        List<String> allowedTables,
        String requestedDuration,
        String justification,
        AccessGrantStatus status,
        Instant expiresAt,
        UUID grantedPermissionId,
        Instant createdAt,
        Instant updatedAt) {
}
