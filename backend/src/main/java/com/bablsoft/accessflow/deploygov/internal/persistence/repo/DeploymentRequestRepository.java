package com.bablsoft.accessflow.deploygov.internal.persistence.repo;

import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentRequestEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DeploymentRequestRepository extends JpaRepository<DeploymentRequestEntity, UUID>,
        JpaSpecificationExecutor<DeploymentRequestEntity> {

    /** The gate lookup: every request for a (pipeline, environment, version), newest first. */
    List<DeploymentRequestEntity> findByPipelineIdAndEnvironmentIdAndVersionOrderByCreatedAtDesc(
            UUID pipelineId, UUID environmentId, String version);

    Optional<DeploymentRequestEntity> findByIdAndOrganizationId(UUID id, UUID organizationId);

    /**
     * Idempotent-trigger lookup — the exact tuple of the partial unique index
     * {@code uq_deployment_requests_trigger_idem} (V150), so a replayed CI run resolves to the
     * request it already created.
     */
    Optional<DeploymentRequestEntity> findByPipelineIdAndEnvironmentIdAndVersionAndExternalRunId(
            UUID pipelineId, UUID environmentId, String version, String externalRunId);
}
