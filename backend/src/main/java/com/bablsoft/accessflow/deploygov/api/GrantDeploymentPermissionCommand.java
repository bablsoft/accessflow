package com.bablsoft.accessflow.deploygov.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Grant (or update, upserting by pipeline + user) a user's trigger permission on a pipeline.
 * A null {@code expiresAt} is a standing grant.
 */
public record GrantDeploymentPermissionCommand(
        UUID userId,
        boolean canTrigger,
        boolean canBreakGlass,
        Instant expiresAt) {
}
