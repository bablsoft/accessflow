package com.bablsoft.accessflow.workflow.internal.persistence.repo;

import com.bablsoft.accessflow.workflow.internal.persistence.entity.RoutingDecisionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface RoutingDecisionRepository extends JpaRepository<RoutingDecisionEntity, UUID> {

    Optional<RoutingDecisionEntity> findByQueryRequestId(UUID queryRequestId);
}
