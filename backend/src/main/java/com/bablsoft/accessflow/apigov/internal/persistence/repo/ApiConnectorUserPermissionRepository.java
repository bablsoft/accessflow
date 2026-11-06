package com.bablsoft.accessflow.apigov.internal.persistence.repo;

import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiConnectorUserPermissionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ApiConnectorUserPermissionRepository
        extends JpaRepository<ApiConnectorUserPermissionEntity, UUID> {

    List<ApiConnectorUserPermissionEntity> findByConnectorId(UUID connectorId);

    Optional<ApiConnectorUserPermissionEntity> findByConnectorIdAndUserId(UUID connectorId, UUID userId);

    List<ApiConnectorUserPermissionEntity> findByUserId(UUID userId);

    void deleteByConnectorIdAndUserId(UUID connectorId, UUID userId);

    /** Grants whose allow-list names {@code operationId} — the grants a removed operation strands (#893). */
    @Query(value = """
            SELECT id FROM api_connector_user_permissions
            WHERE connector_id = :connectorId AND :operationId = ANY(allowed_operations)
            ORDER BY id
            """, nativeQuery = true)
    List<UUID> findIdsByConnectorIdAndAllowedOperation(@Param("connectorId") UUID connectorId,
                                                       @Param("operationId") String operationId);
}
