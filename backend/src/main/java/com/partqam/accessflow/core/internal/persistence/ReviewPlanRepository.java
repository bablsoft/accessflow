package com.partqam.accessflow.core.internal.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ReviewPlanRepository extends JpaRepository<ReviewPlan, UUID> {

    List<ReviewPlan> findAllByOrganization_Id(UUID organizationId);
}
