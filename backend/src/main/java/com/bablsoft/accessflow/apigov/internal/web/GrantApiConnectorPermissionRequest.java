package com.bablsoft.accessflow.apigov.internal.web;

import com.bablsoft.accessflow.apigov.api.GrantApiConnectorPermissionCommand;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record GrantApiConnectorPermissionRequest(
        @NotNull(message = "{validation.api_permission.user.required}")
        UUID userId,
        boolean canRead,
        boolean canWrite,
        boolean canBreakGlass,
        Instant expiresAt,
        List<String> allowedOperations,
        List<String> restrictedResponseFields) {

    GrantApiConnectorPermissionCommand toCommand() {
        return new GrantApiConnectorPermissionCommand(userId, canRead, canWrite, canBreakGlass,
                expiresAt, allowedOperations, restrictedResponseFields);
    }
}
