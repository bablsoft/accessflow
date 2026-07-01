package com.bablsoft.accessflow.lifecycle.internal.persistence.repo;

import com.bablsoft.accessflow.lifecycle.internal.persistence.entity.DeletionRequestDecisionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface DeletionRequestDecisionRepository
        extends JpaRepository<DeletionRequestDecisionEntity, UUID> {

    List<DeletionRequestDecisionEntity> findAllByRequestId(UUID requestId);

    List<DeletionRequestDecisionEntity> findAllByRequestIdOrderByCreatedAtAsc(UUID requestId);

    List<DeletionRequestDecisionEntity> findAllByRequestIdAndStage(UUID requestId, int stage);

    boolean existsByRequestIdAndReviewerId(UUID requestId, UUID reviewerId);
}
