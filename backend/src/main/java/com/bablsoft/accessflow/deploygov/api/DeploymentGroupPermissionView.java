package com.bablsoft.accessflow.deploygov.api;

import java.time.Instant;
import java.util.UUID;

/** Read view of a per-group pipeline trigger grant, denormalizing the group's name and size. */
public record DeploymentGroupPermissionView(
        UUID id,
        UUID pipelineId,
        UUID groupId,
        String groupName,
        long memberCount,
        boolean canTrigger,
        boolean canBreakGlass,
        Instant expiresAt,
        Instant createdAt) {
}
