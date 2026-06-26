package com.bablsoft.accessflow.apigov.internal.web;

import com.bablsoft.accessflow.apigov.api.ApiConnectorPermissionView;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ApiConnectorPermissionResponse(
        UUID id,
        UUID userId,
        String userEmail,
        String userDisplayName,
        boolean canRead,
        boolean canWrite,
        boolean canBreakGlass,
        Instant expiresAt,
        List<String> allowedOperations,
        List<String> restrictedResponseFields,
        Instant createdAt) {

    static ApiConnectorPermissionResponse from(ApiConnectorPermissionView v) {
        return new ApiConnectorPermissionResponse(v.id(), v.userId(), v.userEmail(), v.userDisplayName(),
                v.canRead(), v.canWrite(), v.canBreakGlass(), v.expiresAt(), v.allowedOperations(),
                v.restrictedResponseFields(), v.createdAt());
    }
}
