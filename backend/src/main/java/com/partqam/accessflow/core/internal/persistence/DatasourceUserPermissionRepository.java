package com.partqam.accessflow.core.internal.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DatasourceUserPermissionRepository extends JpaRepository<DatasourceUserPermission, UUID> {

    Optional<DatasourceUserPermission> findByUser_IdAndDatasource_Id(UUID userId, UUID datasourceId);

    List<DatasourceUserPermission> findAllByUser_Id(UUID userId);

    List<DatasourceUserPermission> findAllByDatasource_Id(UUID datasourceId);

    boolean existsByUser_IdAndDatasource_Id(UUID userId, UUID datasourceId);
}
