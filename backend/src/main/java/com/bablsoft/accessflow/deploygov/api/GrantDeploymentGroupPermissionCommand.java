package com.bablsoft.accessflow.deploygov.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Grant (or update, upserting by pipeline + group) a user group's trigger permission on a
 * pipeline. Members inherit the grant through the effective-permission union.
 */
public record GrantDeploymentGroupPermissionCommand(
        UUID groupId,
        boolean canTrigger,
        boolean canBreakGlass,
        Instant expiresAt) {
}
