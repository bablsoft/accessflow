package com.bablsoft.accessflow.apigov.internal.web;

import com.bablsoft.accessflow.apigov.api.GrantApiConnectorGroupPermissionCommand;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record GrantApiConnectorGroupPermissionRequest(
        @NotNull(message = "{validation.api_permission.group.required}")
        UUID groupId,
        boolean canRead,
        boolean canWrite,
        boolean canBreakGlass,
        Instant expiresAt,
        List<String> allowedOperations,
        List<String> restrictedResponseFields) {

    GrantApiConnectorGroupPermissionCommand toCommand() {
        return new GrantApiConnectorGroupPermissionCommand(groupId, canRead, canWrite, canBreakGlass,
                expiresAt, allowedOperations, restrictedResponseFields);
    }
}
