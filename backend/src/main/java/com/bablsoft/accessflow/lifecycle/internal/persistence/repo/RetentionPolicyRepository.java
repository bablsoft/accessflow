package com.bablsoft.accessflow.lifecycle.internal.persistence.repo;

import com.bablsoft.accessflow.lifecycle.internal.persistence.entity.RetentionPolicyEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RetentionPolicyRepository extends JpaRepository<RetentionPolicyEntity, UUID> {

    Optional<RetentionPolicyEntity> findByIdAndOrganizationId(UUID id, UUID organizationId);

    Page<RetentionPolicyEntity> findAllByOrganizationId(UUID organizationId, Pageable pageable);

    List<RetentionPolicyEntity> findAllByEnabledTrue();

    List<RetentionPolicyEntity> findAllByDatasourceIdAndEnabledTrue(UUID datasourceId);
}
