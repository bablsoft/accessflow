package com.bablsoft.accessflow.security.api;

import com.bablsoft.accessflow.core.api.UserRoleType;

import java.time.Instant;
import java.util.UUID;

public record OAuth2ConfigView(
        UUID id,
        UUID organizationId,
        OAuth2ProviderType provider,
        String clientId,
        boolean clientSecretConfigured,
        String scopesOverride,
        String tenantId,
        UserRoleType defaultRole,
        boolean active,
        Instant createdAt,
        Instant updatedAt) {
}
