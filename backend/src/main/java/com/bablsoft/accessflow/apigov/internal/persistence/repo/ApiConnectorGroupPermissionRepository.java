package com.bablsoft.accessflow.apigov.internal.persistence.repo;

import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiConnectorGroupPermissionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ApiConnectorGroupPermissionRepository
        extends JpaRepository<ApiConnectorGroupPermissionEntity, UUID> {

    List<ApiConnectorGroupPermissionEntity> findByConnectorId(UUID connectorId);

    Optional<ApiConnectorGroupPermissionEntity> findByConnectorIdAndGroupId(UUID connectorId,
                                                                            UUID groupId);

    List<ApiConnectorGroupPermissionEntity> findByGroupIdIn(Collection<UUID> groupIds);

    /** Group grants whose allow-list names {@code operationId} (#893). */
    @Query(value = """
            SELECT id FROM api_connector_group_permissions
            WHERE connector_id = :connectorId AND :operationId = ANY(allowed_operations)
            ORDER BY id
            """, nativeQuery = true)
    List<UUID> findIdsByConnectorIdAndAllowedOperation(@Param("connectorId") UUID connectorId,
                                                       @Param("operationId") String operationId);
}
