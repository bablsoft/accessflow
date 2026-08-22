package com.bablsoft.accessflow.deploygov.internal.web;

import com.bablsoft.accessflow.deploygov.api.DeploymentPermissionView;

import java.time.Instant;
import java.util.UUID;

public record DeploymentPermissionResponse(
        UUID id,
        UUID pipelineId,
        UUID userId,
        String userEmail,
        String userDisplayName,
        boolean canTrigger,
        boolean canBreakGlass,
        Instant expiresAt,
        Instant createdAt) {

    static DeploymentPermissionResponse from(DeploymentPermissionView view) {
        return new DeploymentPermissionResponse(view.id(), view.pipelineId(), view.userId(),
                view.userEmail(), view.userDisplayName(), view.canTrigger(), view.canBreakGlass(),
                view.expiresAt(), view.createdAt());
    }
}
