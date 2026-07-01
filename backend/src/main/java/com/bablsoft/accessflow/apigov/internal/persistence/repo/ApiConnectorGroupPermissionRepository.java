package com.bablsoft.accessflow.apigov.internal.persistence.repo;

import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiConnectorGroupPermissionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
