package com.bablsoft.accessflow.security.internal.web;

import com.bablsoft.accessflow.core.api.OrganizationSetupLookupService;
import com.bablsoft.accessflow.core.api.RolePermissionResolver;
import com.bablsoft.accessflow.core.api.UserView;
import com.bablsoft.accessflow.security.internal.web.model.UserSummary;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Builds the {@link UserSummary} every authentication response carries — password login, refresh,
 * SAML and OAuth2 code exchange alike — so the three controllers cannot drift on what a session
 * payload contains. Resolves the role's functional permissions and the organization's
 * governance-domain hints (#926) in one place.
 */
@Component
@RequiredArgsConstructor
class UserSummaryFactory {

    private final RolePermissionResolver rolePermissionResolver;
    private final OrganizationSetupLookupService organizationSetupLookupService;

    UserSummary of(UserView user) {
        return UserSummary.from(
                user,
                rolePermissionResolver.resolve(user.roleId(), user.role()),
                organizationSetupLookupService.governsApis(user.organizationId()),
                organizationSetupLookupService.governsDeployments(user.organizationId()));
    }
}
