package com.bablsoft.accessflow.deploygov.api;

import java.time.Instant;
import java.util.UUID;

/** Read view of a per-user pipeline trigger grant, denormalizing the target user's identity. */
public record DeploymentPermissionView(
        UUID id,
        UUID pipelineId,
        UUID userId,
        String userEmail,
        String userDisplayName,
        boolean canTrigger,
        boolean canBreakGlass,
        Instant expiresAt,
        Instant createdAt) {
}
