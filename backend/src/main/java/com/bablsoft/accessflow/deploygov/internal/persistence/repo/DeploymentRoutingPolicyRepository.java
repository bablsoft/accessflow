package com.bablsoft.accessflow.deploygov.internal.persistence.repo;

import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentRoutingPolicyEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DeploymentRoutingPolicyRepository
        extends JpaRepository<DeploymentRoutingPolicyEntity, UUID> {

    /** Evaluation order: the engine takes the first match. */
    List<DeploymentRoutingPolicyEntity> findByOrganizationIdAndEnabledTrueOrderByPriorityAsc(
            UUID organizationId);

    List<DeploymentRoutingPolicyEntity> findByOrganizationIdOrderByPriorityAsc(UUID organizationId);

    Optional<DeploymentRoutingPolicyEntity> findByIdAndOrganizationId(UUID id, UUID organizationId);

    /** Backs the service-level priority-conflict guard ahead of the unique index (V152). */
    Optional<DeploymentRoutingPolicyEntity> findByOrganizationIdAndPriority(UUID organizationId,
                                                                           int priority);
}
