package com.bablsoft.accessflow.access.internal.web;

import com.bablsoft.accessflow.access.api.AccessGrantStatus;
import com.bablsoft.accessflow.access.api.AccessRequestView;
import com.bablsoft.accessflow.access.api.AccessResourceKind;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AccessRequestResponse(
        UUID id,
        AccessResourceKind resourceKind,
        UUID datasourceId,
        String datasourceName,
        UUID connectorId,
        String connectorName,
        UUID requesterId,
        String requesterEmail,
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

    public static AccessRequestResponse from(AccessRequestView view) {
        return new AccessRequestResponse(
                view.id(),
                view.resourceKind(),
                view.datasourceId(),
                view.datasourceName(),
                view.connectorId(),
                view.connectorName(),
                view.requesterId(),
                view.requesterEmail(),
                view.canRead(),
                view.canWrite(),
                view.canDdl(),
                view.allowedSchemas(),
                view.allowedTables(),
                view.allowedOperations(),
                view.requestedDuration(),
                view.justification(),
                view.preApproveQueries(),
                view.status(),
                view.expiresAt(),
                view.grantedPermissionId(),
                view.createdAt(),
                view.updatedAt());
    }
}
