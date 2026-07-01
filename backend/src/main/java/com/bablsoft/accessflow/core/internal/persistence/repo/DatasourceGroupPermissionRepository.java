package com.bablsoft.accessflow.core.internal.persistence.repo;

import com.bablsoft.accessflow.core.internal.persistence.entity.DatasourceGroupPermissionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DatasourceGroupPermissionRepository
        extends JpaRepository<DatasourceGroupPermissionEntity, UUID> {

    Optional<DatasourceGroupPermissionEntity> findByGroup_IdAndDatasource_Id(UUID groupId,
                                                                             UUID datasourceId);

    List<DatasourceGroupPermissionEntity> findAllByDatasource_Id(UUID datasourceId);

    List<DatasourceGroupPermissionEntity> findAllByGroup_IdIn(Collection<UUID> groupIds);

    List<DatasourceGroupPermissionEntity> findAllByGroup_IdInAndCanBreakGlassTrue(
            Collection<UUID> groupIds);

    boolean existsByGroup_IdAndDatasource_Id(UUID groupId, UUID datasourceId);
}
