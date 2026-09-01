package com.bablsoft.accessflow.deploygov.internal.persistence.repo;

import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentEnvironmentVersionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * One row per environment (unique {@code environment_id}). Org-wide reads with optional
 * pipeline/tag filters go through {@code DeploymentEnvironmentVersionSpecifications} — the
 * tag lives on {@code deployment_environments}, so the specification correlates the two tables
 * (#742 consumes these; there is no controller in #741).
 */
public interface DeploymentEnvironmentVersionRepository
        extends JpaRepository<DeploymentEnvironmentVersionEntity, UUID>,
        JpaSpecificationExecutor<DeploymentEnvironmentVersionEntity> {

    Optional<DeploymentEnvironmentVersionEntity> findByEnvironmentId(UUID environmentId);

    List<DeploymentEnvironmentVersionEntity> findByPipelineId(UUID pipelineId);
}
