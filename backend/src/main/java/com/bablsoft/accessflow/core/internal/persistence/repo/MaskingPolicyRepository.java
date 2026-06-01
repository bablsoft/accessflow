package com.bablsoft.accessflow.core.internal.persistence.repo;

import com.bablsoft.accessflow.core.internal.persistence.entity.MaskingPolicyEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MaskingPolicyRepository extends JpaRepository<MaskingPolicyEntity, UUID> {

    List<MaskingPolicyEntity> findAllByOrganizationIdAndDatasourceIdOrderByCreatedAtAsc(
            UUID organizationId, UUID datasourceId);

    List<MaskingPolicyEntity> findAllByOrganizationIdAndDatasourceIdAndEnabledTrue(
            UUID organizationId, UUID datasourceId);

    Optional<MaskingPolicyEntity> findByIdAndOrganizationId(UUID id, UUID organizationId);
}
