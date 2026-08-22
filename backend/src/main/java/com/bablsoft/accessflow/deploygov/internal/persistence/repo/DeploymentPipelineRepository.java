package com.bablsoft.accessflow.deploygov.internal.persistence.repo;

import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentPipelineEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface DeploymentPipelineRepository extends JpaRepository<DeploymentPipelineEntity, UUID> {
}
