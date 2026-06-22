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

    @Override
    @Transactional(readOnly = true)
    public List<DatasourceUserPermissionView> findBreakGlassEligible(UUID userId) {
        var now = java.time.Instant.now();
        return permissionRepository.findAllByUser_IdAndCanBreakGlassTrue(userId).stream()
                .filter(p -> p.getExpiresAt() == null || p.getExpiresAt().isAfter(now))
                .map(DefaultDatasourceUserPermissionLookupService::toView)
                .toList();
    }

    private static DatasourceUserPermissionView toView(DatasourceUserPermissionEntity entity) {
        return new DatasourceUserPermissionView(
                entity.getId(),
                entity.getUser().getId(),
                entity.getDatasource().getId(),
                entity.isCanRead(),
                entity.isCanWrite(),
                entity.isCanDdl(),
                entity.isCanBreakGlass(),
                toList(entity.getAllowedSchemas()),
                toList(entity.getAllowedTables()),
                toList(entity.getRestrictedColumns()),
                entity.getExpiresAt());
    }

    private static List<String> toList(String[] array) {
        return array == null ? List.of() : List.of(array);
    }
}
