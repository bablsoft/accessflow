package com.bablsoft.accessflow.workflow.internal.persistence.repo;

import com.bablsoft.accessflow.workflow.internal.persistence.entity.BreakGlassEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;
import java.util.UUID;

public interface BreakGlassEventRepository
        extends JpaRepository<BreakGlassEventEntity, UUID>,
        JpaSpecificationExecutor<BreakGlassEventEntity> {

    Optional<BreakGlassEventEntity> findByIdAndOrganizationId(UUID id, UUID organizationId);

    Optional<BreakGlassEventEntity> findByQueryRequestId(UUID queryRequestId);
}
