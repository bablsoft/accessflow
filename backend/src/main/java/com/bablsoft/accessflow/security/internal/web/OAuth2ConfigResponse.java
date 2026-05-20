package com.bablsoft.accessflow.security.internal.web;

import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.security.api.OAuth2ConfigView;
import com.bablsoft.accessflow.security.api.OAuth2ProviderType;
import com.bablsoft.accessflow.security.api.UpdateOAuth2ConfigCommand;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

record OAuth2ConfigResponse(
        UUID id,
        UUID organizationId,
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
        List<String> allowedOrganizations,
        List<String> allowedEmailDomains,
        UserRoleType defaultRole,
        boolean active,
        Instant createdAt,
        Instant updatedAt) {

    static OAuth2ConfigResponse from(OAuth2ConfigView view) {
        return new OAuth2ConfigResponse(
                view.id(),
                view.organizationId(),
                view.provider(),
                view.clientId(),
                view.clientSecretConfigured() ? UpdateOAuth2ConfigCommand.MASKED_SECRET : null,
                view.scopesOverride(),
                view.tenantId(),
                view.displayName(),
                view.authorizationUri(),
                view.tokenUri(),
                view.userInfoUri(),
                view.jwkSetUri(),
                view.issuerUri(),
                view.userNameAttribute(),
                view.emailAttribute(),
                view.emailVerifiedAttribute(),
                view.displayNameAttribute(),
                view.groupsAttribute(),
                view.allowedOrganizations(),
                view.allowedEmailDomains(),
                view.defaultRole(),
                view.active(),
                view.createdAt(),
                view.updatedAt());
    }
}
