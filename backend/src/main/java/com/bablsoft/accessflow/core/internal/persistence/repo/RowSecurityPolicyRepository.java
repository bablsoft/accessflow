package com.bablsoft.accessflow.core.internal.persistence.repo;

import com.bablsoft.accessflow.core.internal.persistence.entity.RowSecurityPolicyEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RowSecurityPolicyRepository extends JpaRepository<RowSecurityPolicyEntity, UUID> {

    List<RowSecurityPolicyEntity> findAllByOrganizationIdAndDatasourceIdOrderByCreatedAtAsc(
            UUID organizationId, UUID datasourceId);

    List<RowSecurityPolicyEntity> findAllByOrganizationIdAndDatasourceIdAndEnabledTrue(
            UUID organizationId, UUID datasourceId);

    Optional<RowSecurityPolicyEntity> findByIdAndOrganizationId(UUID id, UUID organizationId);
}
