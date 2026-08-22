package com.bablsoft.accessflow.deploygov.internal.persistence.repo;

import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentPipelineGroupPermissionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DeploymentPipelineGroupPermissionRepository
        extends JpaRepository<DeploymentPipelineGroupPermissionEntity, UUID> {

    List<DeploymentPipelineGroupPermissionEntity> findByPipelineId(UUID pipelineId);

    Optional<DeploymentPipelineGroupPermissionEntity> findByPipelineIdAndGroupId(UUID pipelineId, UUID groupId);

    List<DeploymentPipelineGroupPermissionEntity> findByGroupIdIn(Collection<UUID> groupIds);
}
