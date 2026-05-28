package com.bablsoft.accessflow.security.api;

import com.bablsoft.accessflow.core.api.UserRoleType;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record OAuth2ConfigView(
        UUID id,
        UUID organizationId,
        OAuth2ProviderType provider,
        String clientId,
        boolean clientSecretConfigured,
        String scopesOverride,
        String tenantId,
        String displayName,
        String authorizationUri,
        String tokenUri,
        String userInfoUri,
        String jwkSetUri,
        String issuerUri,
        String userNameAttribute,
        String emailAttribute,
        String emailVerifiedAttribute,
        String displayNameAttribute,
        String groupsAttribute,
        String baseUrl,
        List<String> allowedOrganizations,
        List<String> allowedEmailDomains,
        Map<String, String> groupMappings,
        UserRoleType defaultRole,
        boolean active,
        Instant createdAt,
        Instant updatedAt) {
}
