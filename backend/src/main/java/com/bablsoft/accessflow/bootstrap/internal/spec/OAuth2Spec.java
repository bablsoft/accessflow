package com.bablsoft.accessflow.bootstrap.internal.spec;

import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.security.api.OAuth2ProviderType;

import java.util.List;
import java.util.Map;

public record OAuth2Spec(
        OAuth2ProviderType provider,
        String clientId,
        String clientSecret,
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
        Boolean active
) {
}
