package com.bablsoft.accessflow.core.internal.persistence.repo;

import com.bablsoft.accessflow.core.internal.persistence.entity.DatasourceUserPermissionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DatasourceUserPermissionRepository extends JpaRepository<DatasourceUserPermissionEntity, UUID> {

    Optional<DatasourceUserPermissionEntity> findByUser_IdAndDatasource_Id(UUID userId, UUID datasourceId);

    List<DatasourceUserPermissionEntity> findAllByUser_Id(UUID userId);

    List<DatasourceUserPermissionEntity> findAllByUser_IdAndCanBreakGlassTrue(UUID userId);

    List<DatasourceUserPermissionEntity> findAllByDatasource_Id(UUID datasourceId);

    // #968: every direct break-glass row in an organization, expired or not — the lookup filters expiry.
    List<DatasourceUserPermissionEntity> findAllByDatasource_Organization_IdAndCanBreakGlassTrue(
            UUID organizationId);

    boolean existsByUser_IdAndDatasource_Id(UUID userId, UUID datasourceId);
}
