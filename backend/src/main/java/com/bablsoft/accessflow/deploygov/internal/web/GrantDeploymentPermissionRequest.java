package com.bablsoft.accessflow.deploygov.internal.web;

import com.bablsoft.accessflow.deploygov.api.GrantDeploymentPermissionCommand;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.UUID;

public record GrantDeploymentPermissionRequest(
        @NotNull(message = "{validation.deployment_permission.user.required}")
        UUID userId,
        Boolean canTrigger,
        Boolean canBreakGlass,
        Instant expiresAt) {

    GrantDeploymentPermissionCommand toCommand() {
        return new GrantDeploymentPermissionCommand(userId, Boolean.TRUE.equals(canTrigger),
                Boolean.TRUE.equals(canBreakGlass), expiresAt);
    }
}
