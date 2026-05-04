package com.partqam.accessflow.security.internal.web.model;

import com.partqam.accessflow.core.api.AuthProviderType;
import com.partqam.accessflow.core.api.UserRoleType;
import com.partqam.accessflow.core.api.UserView;

import java.time.Instant;
import java.util.UUID;

public record AdminUserResponse(
        UUID id,
        String email,
        String displayName,
        UserRoleType role,
        AuthProviderType authProvider,
        boolean active,
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
                view.lastLoginAt(),
                view.createdAt()
        );
    }
}
