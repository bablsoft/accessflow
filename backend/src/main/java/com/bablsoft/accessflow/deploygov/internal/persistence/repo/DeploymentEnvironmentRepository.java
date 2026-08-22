package com.bablsoft.accessflow.deploygov.internal.persistence.repo;

import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentEnvironmentEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface DeploymentEnvironmentRepository
        extends JpaRepository<DeploymentEnvironmentEntity, UUID> {

    List<DeploymentEnvironmentEntity> findByPipelineIdOrderBySortOrderAscNameAsc(UUID pipelineId);

    boolean existsByPipelineIdAndName(UUID pipelineId, String name);
}
