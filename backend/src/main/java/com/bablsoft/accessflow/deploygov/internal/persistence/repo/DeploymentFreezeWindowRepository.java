package com.bablsoft.accessflow.deploygov.internal.persistence.repo;

import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentFreezeWindowEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface DeploymentFreezeWindowRepository
        extends JpaRepository<DeploymentFreezeWindowEntity, UUID> {
}
