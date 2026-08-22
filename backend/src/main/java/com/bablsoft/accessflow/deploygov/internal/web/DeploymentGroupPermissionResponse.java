package com.bablsoft.accessflow.deploygov.internal.web;

import com.bablsoft.accessflow.deploygov.api.DeploymentGroupPermissionView;

import java.time.Instant;
import java.util.UUID;

public record DeploymentGroupPermissionResponse(
        UUID id,
        UUID pipelineId,
        UUID groupId,
        String groupName,
        long memberCount,
        boolean canTrigger,
        boolean canBreakGlass,
        Instant expiresAt,
        Instant createdAt) {

    static DeploymentGroupPermissionResponse from(DeploymentGroupPermissionView view) {
        return new DeploymentGroupPermissionResponse(view.id(), view.pipelineId(), view.groupId(),
                view.groupName(), view.memberCount(), view.canTrigger(), view.canBreakGlass(),
                view.expiresAt(), view.createdAt());
    }
}
