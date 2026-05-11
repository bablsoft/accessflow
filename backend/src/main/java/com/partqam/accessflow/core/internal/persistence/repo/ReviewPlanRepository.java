package com.partqam.accessflow.core.internal.persistence.repo;

import com.partqam.accessflow.core.internal.persistence.entity.ReviewPlanEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ReviewPlanRepository extends JpaRepository<ReviewPlanEntity, UUID> {

    List<ReviewPlanEntity> findAllByOrganization_Id(UUID organizationId);

    List<ReviewPlanEntity> findAllByOrganization_IdOrderByNameAsc(UUID organizationId);

    boolean existsByOrganization_Id(UUID organizationId);

    boolean existsByOrganization_IdAndNameIgnoreCase(UUID organizationId, String name);

    boolean existsByOrganization_IdAndNameIgnoreCaseAndIdNot(UUID organizationId, String name,
                                                             UUID id);
}
