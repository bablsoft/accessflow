package com.bablsoft.accessflow.core.api;

import java.util.UUID;

/**
 * Lightweight existence checks scoped to an organization, used by the system-setup feature to
 * compute admin onboarding progress without pulling in {@code core/internal} JPA entities.
 */
public interface OrganizationSetupLookupService {

    boolean hasAnyDatasource(UUID organizationId);

    boolean hasAnyReviewPlan(UUID organizationId);
}
