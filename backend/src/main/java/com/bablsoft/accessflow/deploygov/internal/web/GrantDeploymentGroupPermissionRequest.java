package com.bablsoft.accessflow.deploygov.internal.web;

import com.bablsoft.accessflow.deploygov.api.GrantDeploymentGroupPermissionCommand;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.UUID;

public record GrantDeploymentGroupPermissionRequest(
        @NotNull(message = "{validation.deployment_permission.group.required}")
        UUID groupId,
        Boolean canTrigger,
        Boolean canBreakGlass,
        Instant expiresAt) {

    GrantDeploymentGroupPermissionCommand toCommand() {
        return new GrantDeploymentGroupPermissionCommand(groupId, Boolean.TRUE.equals(canTrigger),
                Boolean.TRUE.equals(canBreakGlass), expiresAt);
    }
}
