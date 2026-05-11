package com.partqam.accessflow.core.api;

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
        Instant createdAt
) {}
