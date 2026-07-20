package com.bablsoft.accessflow.apigov.api;

import java.time.Instant;
import java.util.List;

/**
 * Update an existing API connector group permission in place. The target group is fixed by the
 * permission id, so — unlike {@link GrantApiConnectorGroupPermissionCommand} — there is no
 * {@code groupId} field; the grant's provenance ({@code createdBy} / {@code createdAt}) is preserved.
 */
public record UpdateApiConnectorGroupPermissionCommand(
        boolean canRead,
        boolean canWrite,
        boolean canBreakGlass,
        boolean canOverrideVariables,
        Instant expiresAt,
        List<String> allowedOperations,
        List<String> restrictedResponseFields) {
}
