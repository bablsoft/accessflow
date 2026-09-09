package com.bablsoft.accessflow.api.internal;

import com.bablsoft.accessflow.core.api.OrganizationAdminService;
import com.bablsoft.accessflow.core.api.OrganizationSetupLookupService;
import com.bablsoft.accessflow.core.api.UpdateOrganizationCommand;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Org-scoped read/write of the two governance-domain flags (#926). Writes go through
 * {@link OrganizationAdminService#update} — the same command the platform-admin organization page
 * uses — with every quota field left null so this path can only ever move the two flags. The
 * organization id always comes from the caller's own JWT claims, never from the request body, so
 * this cannot reach another tenant.
 */
@Service
@RequiredArgsConstructor
class DefaultGovernanceDomainsService implements GovernanceDomainsService {

    private final OrganizationSetupLookupService organizationSetupLookupService;
    private final OrganizationAdminService organizationAdminService;

    @Override
    @Transactional(readOnly = true)
    public GovernanceDomainsView get(UUID organizationId) {
        return new GovernanceDomainsView(
                organizationSetupLookupService.governsApis(organizationId),
                organizationSetupLookupService.governsDeployments(organizationId));
    }

    @Override
    @Transactional
    public GovernanceDomainsView update(UUID organizationId, boolean governsApis,
                                        boolean governsDeployments) {
        var updated = organizationAdminService.update(organizationId, new UpdateOrganizationCommand(
                null, null, null, null, governsApis, governsDeployments));
        return new GovernanceDomainsView(updated.governsApis(), updated.governsDeployments());
    }
}
