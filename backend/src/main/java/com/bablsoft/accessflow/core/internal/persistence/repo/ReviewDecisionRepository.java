package com.bablsoft.accessflow.core.internal.persistence.repo;

import com.bablsoft.accessflow.core.internal.persistence.entity.ReviewDecisionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ReviewDecisionRepository extends JpaRepository<ReviewDecisionEntity, UUID> {

    List<ReviewDecisionEntity> findAllByQueryRequest_IdOrderByDecidedAtAsc(UUID queryRequestId);

    List<ReviewDecisionEntity> findAllByQueryRequest_IdAndStage(UUID queryRequestId, int stage);
}
