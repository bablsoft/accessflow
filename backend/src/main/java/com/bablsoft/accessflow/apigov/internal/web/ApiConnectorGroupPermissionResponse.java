package com.bablsoft.accessflow.apigov.internal.web;

import com.bablsoft.accessflow.apigov.api.ApiConnectorGroupPermissionView;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ApiConnectorGroupPermissionResponse(
        UUID id,
        UUID connectorId,
        UUID groupId,
        String groupName,
        long memberCount,
        boolean canRead,
        boolean canWrite,
        boolean canBreakGlass,
        Instant expiresAt,
        List<String> allowedOperations,
        List<String> restrictedResponseFields,
        Instant createdAt) {

    static ApiConnectorGroupPermissionResponse from(ApiConnectorGroupPermissionView v) {
        return new ApiConnectorGroupPermissionResponse(v.id(), v.connectorId(), v.groupId(),
                v.groupName(), v.memberCount(), v.canRead(), v.canWrite(), v.canBreakGlass(),
                v.expiresAt(), v.allowedOperations(), v.restrictedResponseFields(), v.createdAt());
    }
}
