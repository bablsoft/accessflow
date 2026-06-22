package com.bablsoft.accessflow.ai.internal.persistence.repo;

import com.bablsoft.accessflow.ai.internal.persistence.entity.BehaviorBaselineEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface BehaviorBaselineRepository extends JpaRepository<BehaviorBaselineEntity, UUID> {

    Optional<BehaviorBaselineEntity> findByOrganizationIdAndUserIdAndDatasourceId(
            UUID organizationId, UUID userId, UUID datasourceId);
}
