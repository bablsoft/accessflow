package com.bablsoft.accessflow.deploygov.internal.web;

import com.bablsoft.accessflow.deploygov.api.UpdateDeploymentPermissionCommand;

import java.time.Instant;

public record UpdateDeploymentPermissionRequest(
        Boolean canTrigger,
        Boolean canBreakGlass,
        Instant expiresAt) {

    UpdateDeploymentPermissionCommand toCommand() {
        return new UpdateDeploymentPermissionCommand(Boolean.TRUE.equals(canTrigger),
                Boolean.TRUE.equals(canBreakGlass), expiresAt);
    }
}
