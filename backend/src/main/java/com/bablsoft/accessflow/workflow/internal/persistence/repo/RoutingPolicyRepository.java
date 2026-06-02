package com.bablsoft.accessflow.workflow.internal.persistence.repo;

import com.bablsoft.accessflow.workflow.internal.persistence.entity.RoutingPolicyEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RoutingPolicyRepository extends JpaRepository<RoutingPolicyEntity, UUID> {

    List<RoutingPolicyEntity> findAllByOrganizationIdOrderByPriorityAsc(UUID organizationId);

    Optional<RoutingPolicyEntity> findByIdAndOrganizationId(UUID id, UUID organizationId);

    Optional<RoutingPolicyEntity> findByOrganizationIdAndPriority(UUID organizationId, int priority);

    /**
     * Enabled policies that apply to a query on the given datasource — org-wide ({@code datasource_id
     * IS NULL}) plus those bound to that datasource — ordered for the first-match-by-priority scan.
     */
    @Query("select p from RoutingPolicyEntity p where p.organizationId = :organizationId "
            + "and p.enabled = true and (p.datasourceId is null or p.datasourceId = :datasourceId) "
            + "order by p.priority asc")
    List<RoutingPolicyEntity> findEnabledForEvaluation(
            @Param("organizationId") UUID organizationId,
            @Param("datasourceId") UUID datasourceId);
}
