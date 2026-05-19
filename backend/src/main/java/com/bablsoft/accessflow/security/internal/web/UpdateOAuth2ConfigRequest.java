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
                allowedOrganizations,
                allowedEmailDomains,
                defaultRole,
                active);
    }
}
