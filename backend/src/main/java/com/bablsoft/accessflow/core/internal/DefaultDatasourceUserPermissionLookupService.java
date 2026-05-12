package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.core.api.DatasourceUserPermissionLookupService;
import com.bablsoft.accessflow.core.api.DatasourceUserPermissionView;
import com.bablsoft.accessflow.core.internal.persistence.entity.DatasourceUserPermissionEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.DatasourceUserPermissionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
class DefaultDatasourceUserPermissionLookupService implements DatasourceUserPermissionLookupService {

    private final DatasourceUserPermissionRepository permissionRepository;

    @Override
    @Transactional(readOnly = true)
    public Optional<DatasourceUserPermissionView> findFor(UUID userId, UUID datasourceId) {
        return permissionRepository.findByUser_IdAndDatasource_Id(userId, datasourceId)
                .map(DefaultDatasourceUserPermissionLookupService::toView);
    }

    private static DatasourceUserPermissionView toView(DatasourceUserPermissionEntity entity) {
        return new DatasourceUserPermissionView(
                entity.getId(),
                entity.getUser().getId(),
                entity.getDatasource().getId(),
                entity.isCanRead(),
                entity.isCanWrite(),
                entity.isCanDdl(),
                toList(entity.getAllowedSchemas()),
                toList(entity.getAllowedTables()),
                toList(entity.getRestrictedColumns()),
                entity.getExpiresAt());
    }

    private static List<String> toList(String[] array) {
        return array == null ? List.of() : List.of(array);
    }
}
