package com.bablsoft.accessflow.security.internal.web;

import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.security.api.OAuth2ConfigView;
import com.bablsoft.accessflow.security.api.OAuth2ProviderType;
import com.bablsoft.accessflow.security.api.UpdateOAuth2ConfigCommand;

import java.time.Instant;
import java.util.UUID;

record OAuth2ConfigResponse(
        UUID id,
        UUID organizationId,
        OAuth2ProviderType provider,
        String clientId,
        String clientSecret,
        String scopesOverride,
        String tenantId,
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
                view.defaultRole(),
                view.active(),
                view.createdAt(),
                view.updatedAt());
    }
}
