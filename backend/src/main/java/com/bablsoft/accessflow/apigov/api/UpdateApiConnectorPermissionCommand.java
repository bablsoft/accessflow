package com.bablsoft.accessflow.apigov.api;

import java.time.Instant;
import java.util.List;

/**
 * Update an existing API connector permission in place. The target user is fixed by the permission
 * id, so — unlike {@link GrantApiConnectorPermissionCommand} — there is no {@code userId} field; the
 * grant's provenance ({@code createdBy} / {@code createdAt}) is preserved.
 */
public record UpdateApiConnectorPermissionCommand(
        boolean canRead,
        boolean canWrite,
        boolean canBreakGlass,
        boolean canOverrideVariables,
        Instant expiresAt,
        List<String> allowedOperations,
        List<String> restrictedResponseFields) {
}
