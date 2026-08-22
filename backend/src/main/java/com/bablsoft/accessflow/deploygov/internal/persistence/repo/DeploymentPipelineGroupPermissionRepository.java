package com.bablsoft.accessflow.deploygov.internal.persistence.repo;

import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentPipelineGroupPermissionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface DeploymentPipelineGroupPermissionRepository
        extends JpaRepository<DeploymentPipelineGroupPermissionEntity, UUID> {
}
