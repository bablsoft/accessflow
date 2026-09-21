package com.bablsoft.accessflow.deploygov.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Read-only environment lookups for other modules (#877, epic #870) — the promotion ladder a
 * schema change set walks. Modelled on {@code core.api.ReviewPlanLookupService}: narrow, JDK-pure,
 * and the only way a consumer reaches environments without touching {@code deploygov.internal}.
 *
 * <p>Neither method is organization-scoped. A consumer must first resolve the pipeline through
 * {@link DeploymentPipelineLookupService#findPipeline(UUID, UUID)} (or check
 * {@link DeploymentEnvironmentView#pipelineId()} against it) before acting on a result, so a
 * cross-organization id still reads as "not found" — the 404-never-403 shape the module keeps.
 */
public interface DeploymentEnvironmentLookupService {

    /** Every environment of the pipeline in ladder order — {@code sortOrder}, then name. */
    List<DeploymentEnvironmentView> listByPipeline(UUID pipelineId);

    /** One environment by id, or empty when it does not exist. */
    Optional<DeploymentEnvironmentView> findById(UUID environmentId);
}
