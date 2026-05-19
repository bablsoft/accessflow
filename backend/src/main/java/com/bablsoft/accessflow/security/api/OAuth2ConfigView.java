package com.bablsoft.accessflow.security.api;

import com.bablsoft.accessflow.core.api.UserRoleType;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record OAuth2ConfigView(
        UUID id,
        UUID organizationId,
        OAuth2ProviderType provider,
        String clientId,
        boolean clientSecretConfigured,
        String scopesOverride,
        String tenantId,
        List<String> allowedOrganizations,
        List<String> allowedEmailDomains,
        UserRoleType defaultRole,
        boolean active,
        Instant createdAt,
        Instant updatedAt) {
}
