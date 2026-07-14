package com.bablsoft.accessflow.core.api;

import java.time.Instant;
import java.util.UUID;

public record UserView(
        UUID id,
        String email,
        String displayName,
        UserRoleType role,
        UUID roleId,
        String roleName,
        UUID organizationId,
        boolean active,
        AuthProviderType authProvider,
        String passwordHash,
        Instant lastLoginAt,
        String preferredLanguage,
        boolean totpEnabled,
        boolean platformAdmin,
        Instant createdAt
) {
    /**
     * {@code role} is the legacy system-role enum — null for users on a custom role (AF-522).
     * {@code roleName} is always populated: the assigned role's name (system or custom).
     */
    public UserView {
    }

    /** Convenience constructor for a system-role, non-platform-admin user (tests/legacy callers). */
    public UserView(UUID id, String email, String displayName, UserRoleType role,
                    UUID organizationId, boolean active, AuthProviderType authProvider,
                    String passwordHash, Instant lastLoginAt, String preferredLanguage,
                    boolean totpEnabled, Instant createdAt) {
        this(id, email, displayName, role, null, role != null ? role.name() : null,
                organizationId, active, authProvider, passwordHash, lastLoginAt,
                preferredLanguage, totpEnabled, false, createdAt);
    }
}
