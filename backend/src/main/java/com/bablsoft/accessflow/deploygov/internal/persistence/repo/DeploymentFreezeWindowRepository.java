package com.bablsoft.accessflow.deploygov.internal.persistence.repo;

import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentFreezeWindowEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DeploymentFreezeWindowRepository
        extends JpaRepository<DeploymentFreezeWindowEntity, UUID> {

    Page<DeploymentFreezeWindowEntity> findByOrganizationId(UUID organizationId, Pageable pageable);

    Optional<DeploymentFreezeWindowEntity> findByIdAndOrganizationId(UUID id, UUID organizationId);

    List<DeploymentFreezeWindowEntity> findByOrganizationIdAndEnabledTrue(UUID organizationId);
}
