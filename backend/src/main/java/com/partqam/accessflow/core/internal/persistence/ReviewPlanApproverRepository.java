package com.partqam.accessflow.core.internal.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ReviewPlanApproverRepository extends JpaRepository<ReviewPlanApprover, UUID> {

    List<ReviewPlanApprover> findAllByReviewPlan_Id(UUID reviewPlanId);

    List<ReviewPlanApprover> findAllByReviewPlan_IdOrderByStageAsc(UUID reviewPlanId);
}
