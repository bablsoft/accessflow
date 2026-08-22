package com.bablsoft.accessflow.deploygov.api;

import java.time.Instant;

/**
 * Update an existing user grant in place. The target user is fixed by the permission id, so
 * {@code createdBy}/{@code createdAt} provenance is preserved.
 */
public record UpdateDeploymentPermissionCommand(
        boolean canTrigger,
        boolean canBreakGlass,
        Instant expiresAt) {
}
