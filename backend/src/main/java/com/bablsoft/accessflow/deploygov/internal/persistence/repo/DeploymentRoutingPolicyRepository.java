package com.bablsoft.accessflow.deploygov.internal.persistence.repo;

import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentRoutingPolicyEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface DeploymentRoutingPolicyRepository
        extends JpaRepository<DeploymentRoutingPolicyEntity, UUID> {
}
