package com.bablsoft.accessflow.ai.internal.persistence.repo;

import com.bablsoft.accessflow.ai.api.BehaviorAnomalyStatus;
import com.bablsoft.accessflow.ai.internal.persistence.entity.BehaviorAnomalyEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BehaviorAnomalyRepository
        extends JpaRepository<BehaviorAnomalyEntity, UUID>, JpaSpecificationExecutor<BehaviorAnomalyEntity> {

    Optional<BehaviorAnomalyEntity> findByIdAndOrganizationId(UUID id, UUID organizationId);

    boolean existsByOrganizationIdAndUserIdAndDatasourceIdAndStatus(
            UUID organizationId, UUID userId, UUID datasourceId, BehaviorAnomalyStatus status);

    boolean existsByOrganizationIdAndUserIdAndDatasourceIdAndFeatureAndWindowStart(
            UUID organizationId, UUID userId, UUID datasourceId, String feature, Instant windowStart);

    List<BehaviorAnomalyEntity> findByOrganizationIdAndUserIdAndStatus(
            UUID organizationId, UUID userId, BehaviorAnomalyStatus status);

    List<BehaviorAnomalyEntity> findByOrganizationIdAndUserIdAndDatasourceIdAndStatus(
            UUID organizationId, UUID userId, UUID datasourceId, BehaviorAnomalyStatus status);
}
