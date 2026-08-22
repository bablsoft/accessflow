package com.bablsoft.accessflow.deploygov.internal.persistence.repo;

import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentRequestEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface DeploymentRequestRepository extends JpaRepository<DeploymentRequestEntity, UUID> {

    /** The gate lookup: every request for a (pipeline, environment, version), newest first. */
    List<DeploymentRequestEntity> findByPipelineIdAndEnvironmentIdAndVersionOrderByCreatedAtDesc(
            UUID pipelineId, UUID environmentId, String version);
}
