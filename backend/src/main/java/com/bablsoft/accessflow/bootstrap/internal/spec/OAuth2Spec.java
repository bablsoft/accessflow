package com.bablsoft.accessflow.bootstrap.internal.spec;

import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.security.api.OAuth2ProviderType;

public record OAuth2Spec(
        OAuth2ProviderType provider,
        String clientId,
        String clientSecret,
        String scopesOverride,
        String tenantId,
        UserRoleType defaultRole,
        Boolean active
) {
}
