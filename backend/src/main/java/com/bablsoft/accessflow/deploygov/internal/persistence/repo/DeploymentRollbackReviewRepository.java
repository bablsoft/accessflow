package com.bablsoft.accessflow.deploygov.internal.persistence.repo;

import com.bablsoft.accessflow.deploygov.api.DeploymentRollbackReviewStatus;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentRollbackReviewEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface DeploymentRollbackReviewRepository
        extends JpaRepository<DeploymentRollbackReviewEntity, UUID> {

    Optional<DeploymentRollbackReviewEntity> findByIdAndOrganizationId(UUID id, UUID organizationId);

    Optional<DeploymentRollbackReviewEntity> findByDeploymentRequestId(UUID deploymentRequestId);

    Page<DeploymentRollbackReviewEntity> findByOrganizationIdOrderByCreatedAtDesc(
            UUID organizationId, Pageable pageable);

    Page<DeploymentRollbackReviewEntity> findByOrganizationIdAndStatusOrderByCreatedAtDesc(
            UUID organizationId, DeploymentRollbackReviewStatus status, Pageable pageable);
}
