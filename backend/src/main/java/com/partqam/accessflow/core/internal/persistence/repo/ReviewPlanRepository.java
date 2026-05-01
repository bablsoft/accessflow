package com.partqam.accessflow.core.internal.persistence.repo;

import com.partqam.accessflow.core.internal.persistence.entity.ReviewPlanEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ReviewPlanRepository extends JpaRepository<ReviewPlanEntity, UUID> {

    List<ReviewPlanEntity> findAllByOrganization_Id(UUID organizationId);
}
