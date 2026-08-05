package com.bablsoft.accessflow.ai.internal.persistence.repo;

import com.bablsoft.accessflow.ai.internal.persistence.entity.ApprovalPredictionModelEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ApprovalPredictionModelRepository
        extends JpaRepository<ApprovalPredictionModelEntity, UUID> {

    Optional<ApprovalPredictionModelEntity> findByOrganizationId(UUID organizationId);
}
