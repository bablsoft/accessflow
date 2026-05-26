package com.bablsoft.accessflow.security.internal.web;

import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.security.api.UpdateOAuth2ConfigCommand;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

record UpdateOAuth2ConfigRequest(
        @Size(max = 512, message = "{validation.oauth2_config.client_id.max}") String clientId,
        @Size(max = 2048, message = "{validation.oauth2_config.client_secret.max}") String clientSecret,
        @Size(max = 1024, message = "{validation.oauth2_config.scopes_override.max}") String scopesOverride,
        @Size(max = 255, message = "{validation.oauth2_config.tenant_id.max}") String tenantId,
        @Size(max = 255, message = "{validation.oauth2_config.display_name.max}") String displayName,
        @Size(max = 2048, message = "{validation.oauth2_config.authorization_uri.max}") String authorizationUri,
        @Size(max = 2048, message = "{validation.oauth2_config.token_uri.max}") String tokenUri,
        @Size(max = 2048, message = "{validation.oauth2_config.user_info_uri.max}") String userInfoUri,
        @Size(max = 2048, message = "{validation.oauth2_config.jwk_set_uri.max}") String jwkSetUri,
        @Size(max = 2048, message = "{validation.oauth2_config.issuer_uri.max}") String issuerUri,
        @Size(max = 255, message = "{validation.oauth2_config.user_name_attribute.max}") String userNameAttribute,
        @Size(max = 255, message = "{validation.oauth2_config.email_attribute.max}") String emailAttribute,
        @Size(max = 255, message = "{validation.oauth2_config.email_verified_attribute.max}") String emailVerifiedAttribute,
        @Size(max = 255, message = "{validation.oauth2_config.display_name_attribute.max}") String displayNameAttribute,
        @Size(max = 255, message = "{validation.oauth2_config.groups_attribute.max}") String groupsAttribute,
        @Size(max = 2048, message = "{validation.oauth2_config.base_url.max}") String baseUrl,
        @Size(max = 100, message = "{validation.oauth2_config.allowed_organizations.max}")
        List<@NotBlank(message = "{validation.oauth2_config.allowed_entry.blank}")
             @Size(max = 255, message = "{validation.oauth2_config.allowed_entry.max}") String> allowedOrganizations,
        @Size(max = 100, message = "{validation.oauth2_config.allowed_email_domains.max}")
        List<@NotBlank(message = "{validation.oauth2_config.allowed_entry.blank}")
             @Size(max = 255, message = "{validation.oauth2_config.allowed_entry.max}") String> allowedEmailDomains,
        @NotNull(message = "{validation.oauth2_config.default_role.required}") UserRoleType defaultRole,
        @NotNull(message = "{validation.oauth2_config.active.required}") Boolean active) {

    UpdateOAuth2ConfigCommand toCommand() {
        return new UpdateOAuth2ConfigCommand(
                clientId,
                clientSecret,
                scopesOverride,
                tenantId,
                displayName,
                authorizationUri,
                tokenUri,
                userInfoUri,
                jwkSetUri,
                issuerUri,
                userNameAttribute,
                emailAttribute,
                emailVerifiedAttribute,
                displayNameAttribute,
                groupsAttribute,
                baseUrl,
                allowedOrganizations,
                allowedEmailDomains,
                defaultRole,
                active);
    }
}
