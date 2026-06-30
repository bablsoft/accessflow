package com.bablsoft.accessflow.requestgroups.internal.persistence.repo;

import com.bablsoft.accessflow.core.api.DecisionType;
import com.bablsoft.accessflow.requestgroups.internal.persistence.entity.GroupReviewDecisionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GroupReviewDecisionRepository extends JpaRepository<GroupReviewDecisionEntity, UUID> {

    List<GroupReviewDecisionEntity> findByRequestGroupIdOrderByStageAscDecidedAtAsc(UUID requestGroupId);

    Optional<GroupReviewDecisionEntity> findByRequestGroupIdAndReviewerIdAndStage(
            UUID requestGroupId, UUID reviewerId, int stage);

    long countByRequestGroupIdAndStageAndDecision(UUID requestGroupId, int stage, DecisionType decision);
}
