package com.bablsoft.accessflow.access.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Read view of an access-grant request, enriched with the requester email and resource
 * (datasource or API connector) name for display. Used for the requester's own list and as the
 * cross-module lookup payload consumed by the notifications, realtime, and audit modules (so they
 * never touch {@code access.internal}). Exactly one of the datasource / connector id pairs is set.
 */
public record AccessRequestView(
        UUID id,
        UUID organizationId,
        UUID requesterId,
        String requesterEmail,
        AccessResourceKind resourceKind,
        UUID datasourceId,
        String datasourceName,
        UUID connectorId,
        String connectorName,
        boolean canRead,
        boolean canWrite,
        boolean canDdl,
        List<String> allowedSchemas,
        List<String> allowedTables,
        List<String> allowedOperations,
        String requestedDuration,
        String justification,
        boolean preApproveQueries,
        AccessGrantStatus status,
        Instant expiresAt,
        UUID grantedPermissionId,
        Instant createdAt,
        Instant updatedAt) {
}
