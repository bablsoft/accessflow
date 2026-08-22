package com.bablsoft.accessflow.deploygov.internal.persistence.repo;

import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentEnvironmentEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface DeploymentEnvironmentRepository
        extends JpaRepository<DeploymentEnvironmentEntity, UUID> {
}
