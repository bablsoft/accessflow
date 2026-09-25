package com.bablsoft.accessflow.requestgroups.internal.persistence.repo;

import com.bablsoft.accessflow.core.api.DecisionType;
import com.bablsoft.accessflow.requestgroups.internal.persistence.entity.GroupReviewDecisionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GroupReviewDecisionRepository extends JpaRepository<GroupReviewDecisionEntity, UUID> {

    List<GroupReviewDecisionEntity> findByRequestGroupIdOrderByStageAscDecidedAtAsc(UUID requestGroupId);

    /** Every decision recorded at a stage — used for the one-authority-one-vote guard (#622). */
    List<GroupReviewDecisionEntity> findByRequestGroupIdAndStage(UUID requestGroupId, int stage);

    Optional<GroupReviewDecisionEntity> findByRequestGroupIdAndReviewerIdAndStage(
            UUID requestGroupId, UUID reviewerId, int stage);

    /** Whether any person approved the group — the review a missing bytes estimate demands (#941). */
    boolean existsByRequestGroupIdAndDecision(UUID requestGroupId, DecisionType decision);

    long countByRequestGroupIdAndStageAndDecision(UUID requestGroupId, int stage, DecisionType decision);
}
