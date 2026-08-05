package com.bablsoft.accessflow.core.internal.persistence.repo;

import com.bablsoft.accessflow.core.internal.persistence.entity.ApprovalPredictionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ApprovalPredictionRepository extends JpaRepository<ApprovalPredictionEntity, UUID> {

    Optional<ApprovalPredictionEntity> findByQueryRequestId(UUID queryRequestId);

    List<ApprovalPredictionEntity> findByQueryRequestIdIn(Collection<UUID> queryRequestIds);
}
