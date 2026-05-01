package com.partqam.accessflow.core;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DatasourceUserPermissionRepository extends JpaRepository<DatasourceUserPermission, UUID> {

    Optional<DatasourceUserPermission> findByUserIdAndDatasourceId(UUID userId, UUID datasourceId);

    List<DatasourceUserPermission> findAllByUserId(UUID userId);

    List<DatasourceUserPermission> findAllByDatasourceId(UUID datasourceId);

    boolean existsByUserIdAndDatasourceId(UUID userId, UUID datasourceId);
}
