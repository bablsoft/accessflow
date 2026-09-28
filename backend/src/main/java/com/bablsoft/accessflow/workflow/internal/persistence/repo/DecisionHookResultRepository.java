package com.bablsoft.accessflow.workflow.internal.persistence.repo;

import com.bablsoft.accessflow.workflow.internal.persistence.entity.DecisionHookResultEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface DecisionHookResultRepository extends JpaRepository<DecisionHookResultEntity, UUID> {

    Optional<DecisionHookResultEntity> findByQueryRequestId(UUID queryRequestId);
}
