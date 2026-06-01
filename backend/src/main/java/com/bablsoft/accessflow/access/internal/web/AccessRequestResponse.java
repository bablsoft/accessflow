package com.bablsoft.accessflow.access.internal.web;

import com.bablsoft.accessflow.access.api.AccessGrantStatus;
import com.bablsoft.accessflow.access.api.AccessRequestView;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AccessRequestResponse(
        UUID id,
        UUID datasourceId,
        String datasourceName,
        UUID requesterId,
        String requesterEmail,
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

    public static AccessRequestResponse from(AccessRequestView view) {
        return new AccessRequestResponse(
                view.id(),
                view.datasourceId(),
                view.datasourceName(),
                view.requesterId(),
                view.requesterEmail(),
                view.canRead(),
                view.canWrite(),
                view.canDdl(),
                view.allowedSchemas(),
                view.allowedTables(),
                view.requestedDuration(),
                view.justification(),
                view.status(),
                view.expiresAt(),
                view.grantedPermissionId(),
                view.createdAt(),
                view.updatedAt());
    }
}
