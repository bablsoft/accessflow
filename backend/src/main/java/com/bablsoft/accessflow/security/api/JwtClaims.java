package com.bablsoft.accessflow.security.api;

import com.bablsoft.accessflow.core.api.Permission;
import com.bablsoft.accessflow.core.api.SystemRolePermissions;
import com.bablsoft.accessflow.core.api.UserRoleType;

import java.util.Set;
import java.util.UUID;

/**
 * The authenticated principal (AF-522). {@code role} is the legacy system-role enum — null for a
 * user on a custom role; {@code roleName} is always populated. {@code permissions} is the resolved
 * functional permission set of the user's role, minted into the access token (JWT path) or
 * resolved live per request (API-key path).
 */
public record JwtClaims(
        UUID userId,
        String email,
        UserRoleType role,
        UUID roleId,
        String roleName,
        Set<Permission> permissions,
        UUID organizationId,
        boolean platformAdmin
) {

    /** Convenience factory for a principal on a system role (tests and legacy callers). */
    public static JwtClaims forSystemRole(UUID userId, String email, UserRoleType role,
                                          UUID organizationId, boolean platformAdmin) {
        return new JwtClaims(userId, email, role, null, role != null ? role.name() : null,
                role != null ? SystemRolePermissions.of(role) : Set.of(),
                organizationId, platformAdmin);
    }

    /** Convenience factory for a non-platform-admin principal on a system role. */
    public static JwtClaims forSystemRole(UUID userId, String email, UserRoleType role,
                                          UUID organizationId) {
        return forSystemRole(userId, email, role, organizationId, false);
    }

    /** Whether this principal's role grants the given functional permission. */
    public boolean has(Permission permission) {
        return permissions != null && permissions.contains(permission);
    }
}
