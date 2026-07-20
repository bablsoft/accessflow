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
        /** AF-613. Boxed so an existing client that omits it still works; null means false. */
        Boolean canOverrideVariables,
        Instant expiresAt,
        List<String> allowedOperations,
        List<String> restrictedResponseFields) {

    GrantApiConnectorPermissionCommand toCommand() {
        return new GrantApiConnectorPermissionCommand(userId, canRead, canWrite, canBreakGlass,
                Boolean.TRUE.equals(canOverrideVariables), expiresAt, allowedOperations, restrictedResponseFields);
    }
}
