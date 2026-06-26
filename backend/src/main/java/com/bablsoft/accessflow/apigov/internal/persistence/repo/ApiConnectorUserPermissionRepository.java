package com.bablsoft.accessflow.apigov.internal.persistence.repo;

import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiConnectorUserPermissionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ApiConnectorUserPermissionRepository
        extends JpaRepository<ApiConnectorUserPermissionEntity, UUID> {

    List<ApiConnectorUserPermissionEntity> findByConnectorId(UUID connectorId);

    Optional<ApiConnectorUserPermissionEntity> findByConnectorIdAndUserId(UUID connectorId, UUID userId);

    List<ApiConnectorUserPermissionEntity> findByUserId(UUID userId);

    void deleteByConnectorIdAndUserId(UUID connectorId, UUID userId);
}
