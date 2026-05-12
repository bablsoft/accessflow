package com.bablsoft.accessflow.security.internal.web.model;

import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.api.UserView;

import java.time.Instant;
import java.util.UUID;

public record AdminUserResponse(
        UUID id,
        String email,
        String displayName,
        UserRoleType role,
        AuthProviderType authProvider,
        boolean active,
        boolean totpEnabled,
        Instant lastLoginAt,
        Instant createdAt
) {
    public static AdminUserResponse from(UserView view) {
        return new AdminUserResponse(
                view.id(),
                view.email(),
                view.displayName(),
                view.role(),
                view.authProvider(),
                view.active(),
                view.totpEnabled(),
                view.lastLoginAt(),
                view.createdAt()
        );
    }
}
