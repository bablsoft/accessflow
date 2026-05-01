package com.partqam.accessflow.core.internal.persistence.repo;

import com.partqam.accessflow.core.internal.persistence.entity.ReviewPlanApproverEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ReviewPlanApproverRepository extends JpaRepository<ReviewPlanApproverEntity, UUID> {

    List<ReviewPlanApproverEntity> findAllByReviewPlan_Id(UUID reviewPlanId);

    List<ReviewPlanApproverEntity> findAllByReviewPlan_IdOrderByStageAsc(UUID reviewPlanId);
}
