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
        Instant createdAt,
        String scimExternalId,
        Instant updatedAt
) {
    /**
     * {@code role} is the legacy system-role enum — null for users on a custom role (AF-522).
     * {@code roleName} is always populated: the assigned role's name (system or custom).
     * {@code scimExternalId} is the IdP-side SCIM identifier, null unless SCIM-managed (#621);
     * {@code updatedAt} feeds SCIM {@code meta.lastModified} and may be null for views built by
     * legacy callers.
     */
    public UserView {
    }

    /** Convenience constructor for callers that predate the SCIM columns (#621). */
    public UserView(UUID id, String email, String displayName, UserRoleType role, UUID roleId,
                    String roleName, UUID organizationId, boolean active,
                    AuthProviderType authProvider, String passwordHash, Instant lastLoginAt,
                    String preferredLanguage, boolean totpEnabled, boolean platformAdmin,
                    Instant createdAt) {
        this(id, email, displayName, role, roleId, roleName, organizationId, active, authProvider,
                passwordHash, lastLoginAt, preferredLanguage, totpEnabled, platformAdmin,
                createdAt, null, createdAt);
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
