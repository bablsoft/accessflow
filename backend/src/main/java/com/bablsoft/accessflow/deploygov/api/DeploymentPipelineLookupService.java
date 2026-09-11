package com.bablsoft.accessflow.deploygov.api;

import java.util.Optional;
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

    /**
     * One pipeline, scoped to an organization (issue AF-967). Empty when it does not exist or belongs
     * to another organization — the caller cannot tell the two apart, which is the intended
     * 404-never-403 shape the whole module uses.
     */
    Optional<DeploymentPipelineView> findPipeline(UUID pipelineId, UUID organizationId);

    /** One environment of a pipeline, or empty when it is not on that pipeline (issue AF-967). */
    Optional<DeploymentEnvironmentView> findEnvironment(UUID pipelineId, UUID environmentId);
}
