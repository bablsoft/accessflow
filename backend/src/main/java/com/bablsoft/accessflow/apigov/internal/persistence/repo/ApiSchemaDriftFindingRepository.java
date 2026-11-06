package com.bablsoft.accessflow.apigov.internal.persistence.repo;

import com.bablsoft.accessflow.apigov.api.ApiSchemaDriftStatus;
import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiSchemaDriftFindingEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ApiSchemaDriftFindingRepository extends JpaRepository<ApiSchemaDriftFindingEntity, UUID> {

    List<ApiSchemaDriftFindingEntity> findByConnectorIdAndStatusOrderByCreatedAtDesc(
            UUID connectorId, ApiSchemaDriftStatus status);

    List<ApiSchemaDriftFindingEntity> findBySchemaIdAndStatus(UUID schemaId, ApiSchemaDriftStatus status);

    List<ApiSchemaDriftFindingEntity> findByConnectorIdOrderByCreatedAtDesc(UUID connectorId);

    Optional<ApiSchemaDriftFindingEntity> findByIdAndConnectorId(UUID id, UUID connectorId);
}
