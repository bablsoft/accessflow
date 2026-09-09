package com.bablsoft.accessflow.api.internal;

import java.util.UUID;

/** Read and update the organization's governance-domain hints (#926). */
public interface GovernanceDomainsService {

    GovernanceDomainsView get(UUID organizationId);

    /** Both flags are replaced; there is no partial update. */
    GovernanceDomainsView update(UUID organizationId, boolean governsApis, boolean governsDeployments);
}
