package com.bablsoft.accessflow.deploygov.api;

import java.time.Instant;

/**
 * Update an existing group grant in place. The target group is fixed by the permission id, so
 * {@code createdBy}/{@code createdAt} provenance is preserved.
 */
public record UpdateDeploymentGroupPermissionCommand(
        boolean canTrigger,
        boolean canBreakGlass,
        Instant expiresAt) {
}
