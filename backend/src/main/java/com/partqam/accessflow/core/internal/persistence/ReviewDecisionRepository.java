package com.partqam.accessflow.core.internal.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ReviewDecisionRepository extends JpaRepository<ReviewDecision, UUID> {

    List<ReviewDecision> findAllByQueryRequest_IdOrderByDecidedAtAsc(UUID queryRequestId);

    List<ReviewDecision> findAllByQueryRequest_IdAndStage(UUID queryRequestId, int stage);
}
