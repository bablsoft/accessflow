package com.bablsoft.accessflow.deploygov.internal.web;

import com.bablsoft.accessflow.deploygov.api.UpdateDeploymentGroupPermissionCommand;

import java.time.Instant;

public record UpdateDeploymentGroupPermissionRequest(
        Boolean canTrigger,
        Boolean canBreakGlass,
        Instant expiresAt) {

    UpdateDeploymentGroupPermissionCommand toCommand() {
        return new UpdateDeploymentGroupPermissionCommand(Boolean.TRUE.equals(canTrigger),
                Boolean.TRUE.equals(canBreakGlass), expiresAt);
    }
}
