package com.bablsoft.accessflow.bootstrap.internal.spec;

import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.security.api.OAuth2ProviderType;

import java.util.List;

public record OAuth2Spec(
        OAuth2ProviderType provider,
        String clientId,
        String clientSecret,
        String scopesOverride,
        String tenantId,
        List<String> allowedOrganizations,
        List<String> allowedEmailDomains,
        UserRoleType defaultRole,
        Boolean active
) {
}
