package com.bablsoft.accessflow.deploygov.internal.persistence.repo;

import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentPipelineUserPermissionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface DeploymentPipelineUserPermissionRepository
        extends JpaRepository<DeploymentPipelineUserPermissionEntity, UUID> {
}
