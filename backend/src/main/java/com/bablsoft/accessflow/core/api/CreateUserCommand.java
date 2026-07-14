package com.bablsoft.accessflow.core.api;

import java.util.UUID;

/**
 * {@code roleId} (any role visible to the org — system or custom) wins over the legacy {@code role}
 * enum when both are given; at least one must be set (AF-522).
 */
public record CreateUserCommand(
        UUID organizationId,
        String email,
        String displayName,
        String passwordHash,
        UserRoleType role,
        UUID roleId,
        boolean platformAdmin
) {
    /** Legacy convenience constructor for system-role callers (bootstrap, setup wizard, tests). */
    public CreateUserCommand(UUID organizationId, String email, String displayName,
                             String passwordHash, UserRoleType role, boolean platformAdmin) {
        this(organizationId, email, displayName, passwordHash, role, null, platformAdmin);
    }
}
