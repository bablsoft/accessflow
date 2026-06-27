package com.bablsoft.accessflow.apigov.internal.persistence.repo;

import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiReviewDecisionEntity;
import com.bablsoft.accessflow.core.api.DecisionType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ApiReviewDecisionRepository extends JpaRepository<ApiReviewDecisionEntity, UUID> {

    List<ApiReviewDecisionEntity> findByApiRequestIdOrderByStageAscDecidedAtAsc(UUID apiRequestId);

    Optional<ApiReviewDecisionEntity> findByApiRequestIdAndReviewerIdAndStage(UUID apiRequestId,
                                                                             UUID reviewerId, int stage);

    long countByApiRequestIdAndStageAndDecision(UUID apiRequestId, int stage, DecisionType decision);
}
