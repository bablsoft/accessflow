package com.bablsoft.accessflow.security.api;

import com.bablsoft.accessflow.core.api.UserRoleType;

import java.util.UUID;

/** {@code roleId} (system or custom role) wins over the legacy {@code role} enum (AF-522). */
public record InviteUserCommand(
        String email,
        String displayName,
        UserRoleType role,
        UUID roleId
) {
    public InviteUserCommand(String email, String displayName, UserRoleType role) {
        this(email, displayName, role, null);
    }
}
