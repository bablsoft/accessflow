package com.bablsoft.accessflow.deploygov.internal.persistence.repo;

import com.bablsoft.accessflow.core.api.DecisionType;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentReviewDecisionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DeploymentReviewDecisionRepository
        extends JpaRepository<DeploymentReviewDecisionEntity, UUID> {

    List<DeploymentReviewDecisionEntity> findByDeploymentRequestIdOrderByStageAscDecidedAtAsc(
            UUID deploymentRequestId);

    /** The idempotent-replay lookup: one decision per (request, reviewer, stage). */
    Optional<DeploymentReviewDecisionEntity> findByDeploymentRequestIdAndReviewerIdAndStage(
            UUID deploymentRequestId, UUID reviewerId, int stage);

    long countByDeploymentRequestIdAndStageAndDecision(UUID deploymentRequestId, int stage,
                                                       DecisionType decision);
}
