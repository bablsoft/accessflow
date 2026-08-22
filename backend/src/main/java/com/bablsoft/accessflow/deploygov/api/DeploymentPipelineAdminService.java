package com.bablsoft.accessflow.deploygov.api;

import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.PageResponse;

import java.util.List;
import java.util.UUID;

/**
 * Admin CRUD for deployment pipelines and their environments (#688, epic #682). All methods are
 * org-scoped: a pipeline in another organization reads as
 * {@link DeploymentPipelineNotFoundException}, never as "exists elsewhere".
 */
public interface DeploymentPipelineAdminService {

    PageResponse<DeploymentPipelineView> list(UUID organizationId, PageRequest pageRequest);

    DeploymentPipelineView get(UUID id, UUID organizationId);

    DeploymentPipelineView create(CreateDeploymentPipelineCommand command);

    DeploymentPipelineView update(UUID id, UUID organizationId, UpdateDeploymentPipelineCommand command);

    void delete(UUID id, UUID organizationId);

    /** The pipeline's environments, ordered by {@code sortOrder} then name. */
    List<DeploymentEnvironmentView> listEnvironments(UUID pipelineId, UUID organizationId);

    DeploymentEnvironmentView createEnvironment(UUID pipelineId, UUID organizationId,
                                                CreateDeploymentEnvironmentCommand command);

    DeploymentEnvironmentView updateEnvironment(UUID pipelineId, UUID organizationId, UUID environmentId,
                                                UpdateDeploymentEnvironmentCommand command);

    void deleteEnvironment(UUID pipelineId, UUID organizationId, UUID environmentId);
}
