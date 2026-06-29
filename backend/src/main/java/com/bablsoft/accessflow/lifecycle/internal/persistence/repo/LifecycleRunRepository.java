package com.bablsoft.accessflow.lifecycle.internal.persistence.repo;

import com.bablsoft.accessflow.lifecycle.api.LifecycleRunStatus;
import com.bablsoft.accessflow.lifecycle.internal.persistence.entity.LifecycleRunEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface LifecycleRunRepository extends JpaRepository<LifecycleRunEntity, UUID> {

    boolean existsByPolicyIdAndStatus(UUID policyId, LifecycleRunStatus status);
}
