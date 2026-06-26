package com.bablsoft.accessflow.apigov.internal.persistence.repo;

import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiSchemaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ApiSchemaRepository extends JpaRepository<ApiSchemaEntity, UUID> {

    List<ApiSchemaEntity> findByConnectorIdOrderByCreatedAtDesc(UUID connectorId);

    Optional<ApiSchemaEntity> findFirstByConnectorIdOrderByCreatedAtDesc(UUID connectorId);

    Optional<ApiSchemaEntity> findByIdAndConnectorId(UUID id, UUID connectorId);

    void deleteByConnectorId(UUID connectorId);
}
