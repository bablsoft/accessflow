package com.bablsoft.accessflow.security.internal.web;

import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.security.api.UpdateOAuth2ConfigCommand;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

record UpdateOAuth2ConfigRequest(
        @Size(max = 512, message = "{validation.oauth2_config.client_id.max}") String clientId,
        @Size(max = 2048, message = "{validation.oauth2_config.client_secret.max}") String clientSecret,
        @Size(max = 1024, message = "{validation.oauth2_config.scopes_override.max}") String scopesOverride,
        @Size(max = 255, message = "{validation.oauth2_config.tenant_id.max}") String tenantId,
        @NotNull(message = "{validation.oauth2_config.default_role.required}") UserRoleType defaultRole,
        @NotNull(message = "{validation.oauth2_config.active.required}") Boolean active) {

    UpdateOAuth2ConfigCommand toCommand() {
        return new UpdateOAuth2ConfigCommand(
                clientId,
                clientSecret,
                scopesOverride,
                tenantId,
                defaultRole,
                active);
    }
}
