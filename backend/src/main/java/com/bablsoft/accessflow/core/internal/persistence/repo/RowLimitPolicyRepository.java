package com.bablsoft.accessflow.core.internal.persistence.repo;

import com.bablsoft.accessflow.core.internal.persistence.entity.RowLimitPolicyEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RowLimitPolicyRepository extends JpaRepository<RowLimitPolicyEntity, UUID> {

    List<RowLimitPolicyEntity> findAllByOrganizationIdAndDatasourceIdOrderByCreatedAtAsc(
            UUID organizationId, UUID datasourceId);

    List<RowLimitPolicyEntity> findAllByOrganizationIdAndDatasourceIdAndEnabledTrue(
            UUID organizationId, UUID datasourceId);

    Optional<RowLimitPolicyEntity> findByIdAndOrganizationId(UUID id, UUID organizationId);
}
