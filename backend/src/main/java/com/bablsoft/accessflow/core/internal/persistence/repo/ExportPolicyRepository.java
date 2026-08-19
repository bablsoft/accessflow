package com.bablsoft.accessflow.core.internal.persistence.repo;

import com.bablsoft.accessflow.core.internal.persistence.entity.ExportPolicyEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ExportPolicyRepository extends JpaRepository<ExportPolicyEntity, UUID> {

    List<ExportPolicyEntity> findAllByOrganizationIdAndDatasourceIdOrderByCreatedAtAsc(
            UUID organizationId, UUID datasourceId);

    List<ExportPolicyEntity> findAllByOrganizationIdAndDatasourceIdAndEnabledTrue(
            UUID organizationId, UUID datasourceId);

    Optional<ExportPolicyEntity> findByIdAndOrganizationId(UUID id, UUID organizationId);
}
