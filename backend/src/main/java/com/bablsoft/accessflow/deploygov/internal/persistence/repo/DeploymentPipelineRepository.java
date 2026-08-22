package com.bablsoft.accessflow.deploygov.internal.persistence.repo;

import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentPipelineEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface DeploymentPipelineRepository extends JpaRepository<DeploymentPipelineEntity, UUID> {

    boolean existsByOrganizationIdAndName(UUID organizationId, String name);

    Optional<DeploymentPipelineEntity> findByIdAndOrganizationId(UUID id, UUID organizationId);

    Page<DeploymentPipelineEntity> findByOrganizationId(UUID organizationId, Pageable pageable);
}
