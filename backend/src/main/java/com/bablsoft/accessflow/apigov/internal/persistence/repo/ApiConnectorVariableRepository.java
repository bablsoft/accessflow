package com.bablsoft.accessflow.apigov.internal.persistence.repo;

import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiConnectorVariableEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ApiConnectorVariableRepository
        extends JpaRepository<ApiConnectorVariableEntity, UUID> {

    /**
     * Evaluation order: {@code (sort_order, created_at, id)} is a total order, so the topological
     * sort's tie-break between independent variables is deterministic across nodes and restarts.
     */
    List<ApiConnectorVariableEntity>
            findAllByOrganizationIdAndConnectorIdOrderBySortOrderAscCreatedAtAscIdAsc(
                    UUID organizationId, UUID connectorId);

    Optional<ApiConnectorVariableEntity> findByIdAndOrganizationIdAndConnectorId(
            UUID id, UUID organizationId, UUID connectorId);

    long countByOrganizationIdAndConnectorId(UUID organizationId, UUID connectorId);
}
