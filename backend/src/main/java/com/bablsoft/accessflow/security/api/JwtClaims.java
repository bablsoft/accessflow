package com.bablsoft.accessflow.security.api;

import com.bablsoft.accessflow.core.api.UserRoleType;

import java.util.UUID;

public record JwtClaims(
        UUID userId,
        String email,
        UserRoleType role,
        UUID organizationId,
        boolean platformAdmin
) {
    /** Convenience constructor for a non-platform-admin principal. */
    public JwtClaims(UUID userId, String email, UserRoleType role, UUID organizationId) {
        this(userId, email, role, organizationId, false);
    }
}
