package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.core.api.DatasourceConnectionDescriptor;
import com.bablsoft.accessflow.core.api.DatasourceLookupService;
import com.bablsoft.accessflow.core.api.DatasourceRef;
import com.bablsoft.accessflow.core.internal.persistence.entity.DatasourceEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.DatasourceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
class DefaultDatasourceLookupService implements DatasourceLookupService {

    private final DatasourceRepository datasourceRepository;

    @Override
    @Transactional(readOnly = true)
    public Optional<DatasourceConnectionDescriptor> findById(UUID datasourceId) {
        return datasourceRepository.findById(datasourceId)
                .map(DefaultDatasourceLookupService::toDescriptor);
    }

    @Override
    @Transactional(readOnly = true)
    public List<DatasourceRef> findRefsByAiConfigId(UUID aiConfigId) {
        if (aiConfigId == null) {
            return List.of();
        }
        return datasourceRepository.findAllByAiConfigId(aiConfigId).stream()
                .map(d -> new DatasourceRef(d.getId(), d.getName()))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, Integer> countsByAiConfigIds(Set<UUID> aiConfigIds) {
        if (aiConfigIds == null || aiConfigIds.isEmpty()) {
            return Map.of();
        }
        var rows = datasourceRepository.countByAiConfigIdIn(aiConfigIds);
        var counts = new HashMap<UUID, Integer>(rows.size());
        for (var row : rows) {
            var id = (UUID) row[0];
            var count = ((Number) row[1]).intValue();
            counts.put(id, count);
        }
        return counts;
    }

    @Override
    @Transactional(readOnly = true)
    public Set<UUID> findActiveAiAnalysisAiConfigIdsByOrganization(UUID organizationId) {
        if (organizationId == null) {
            return Set.of();
        }
        return Set.copyOf(datasourceRepository
                .findActiveAiAnalysisAiConfigIdsByOrganization(organizationId));
    }

    private static DatasourceConnectionDescriptor toDescriptor(DatasourceEntity entity) {
        return new DatasourceConnectionDescriptor(
                entity.getId(),
                entity.getOrganization().getId(),
                entity.getDbType(),
                entity.getHost(),
                entity.getPort(),
                entity.getDatabaseName(),
                entity.getUsername(),
                entity.getPasswordEncrypted(),
                entity.getSslMode(),
                entity.getConnectionPoolSize(),
                entity.getMaxRowsPerQuery(),
                entity.isAiAnalysisEnabled(),
                entity.getAiConfigId(),
                entity.isActive());
    }
}
