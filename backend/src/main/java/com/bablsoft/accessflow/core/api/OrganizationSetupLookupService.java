package com.bablsoft.accessflow.core.api;

import java.util.UUID;

/**
 * Lightweight existence checks scoped to an organization, used by the system-setup feature to
 * compute admin onboarding progress without pulling in {@code core/internal} JPA entities.
 */
public interface OrganizationSetupLookupService {

    boolean hasAnyDatasource(UUID organizationId);

    boolean hasAnyReviewPlan(UUID organizationId);

    /**
     * Whether the organization opted into API access governance during first-run setup (AF-898).
     * A hint that decides which onboarding steps appear — never an entitlement.
     */
    boolean governsApis(UUID organizationId);

    /** Whether the organization opted into deployment approval governance (AF-898). */
    boolean governsDeployments(UUID organizationId);
}
