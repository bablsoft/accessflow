package com.bablsoft.accessflow.deploygov.api;

import java.util.UUID;

/**
 * Read-only pipeline existence checks for other modules (AF-898). Mirrors
 * {@code apigov.api.ApiConnectorLookupService}: the onboarding checklist needs to know whether the
 * organization has created its first deployment pipeline, without reaching into
 * {@code deploygov/internal}.
 */
public interface DeploymentPipelineLookupService {

    /**
     * Whether the organization owns at least one pipeline, active or not — a pipeline the admin
     * later deactivated still means the onboarding step was done.
     */
    boolean hasAnyPipeline(UUID organizationId);
}
