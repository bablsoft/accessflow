package com.bablsoft.accessflow.deploygov.internal.persistence.repo;

import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentVerificationEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface DeploymentVerificationRepository
        extends JpaRepository<DeploymentVerificationEntity, UUID> {

    List<DeploymentVerificationEntity> findByDeploymentRequestIdOrderBySampledAtAsc(
            UUID deploymentRequestId);

    long countByDeploymentRequestIdAndCheckNameAndPassedFalse(UUID deploymentRequestId,
                                                              String checkName);
}
