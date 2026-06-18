package com.bablsoft.accessflow.core.api;

import java.time.Instant;
import java.util.UUID;

public record UserView(
        UUID id,
        String email,
        String displayName,
        UserRoleType role,
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
    /** Convenience constructor for a non-platform-admin user. */
    public UserView(UUID id, String email, String displayName, UserRoleType role,
                    UUID organizationId, boolean active, AuthProviderType authProvider,
                    String passwordHash, Instant lastLoginAt, String preferredLanguage,
                    boolean totpEnabled, Instant createdAt) {
        this(id, email, displayName, role, organizationId, active, authProvider, passwordHash,
                lastLoginAt, preferredLanguage, totpEnabled, false, createdAt);
    }
}
