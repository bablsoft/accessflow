package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.core.api.DatasourceConnectionDescriptor;
import com.bablsoft.accessflow.core.api.DatasourceLookupService;
import com.bablsoft.accessflow.core.api.DatasourceRef;
import com.bablsoft.accessflow.core.internal.persistence.entity.DatasourceEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.DatasourceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
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
    public Optional<DatasourceRef> findRef(UUID datasourceId) {
        if (datasourceId == null) {
            return Optional.empty();
        }
        return datasourceRepository.findById(datasourceId)
                .map(d -> new DatasourceRef(d.getId(), d.getName()));
    }

    @Override
    @Transactional(readOnly = true)
    public List<DatasourceRef> findActiveRefsByOrganization(UUID organizationId) {
        if (organizationId == null) {
            return List.of();
        }
        return datasourceRepository.findAllByOrganization_IdAndActiveTrue(organizationId).stream()
                .map(d -> new DatasourceRef(d.getId(), d.getName()))
                .sorted(Comparator.comparing(DatasourceRef::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
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

    @Override
    @Transactional(readOnly = true)
    public List<DatasourceConnectionDescriptor> findByCredentialReference(String reference) {
        if (reference == null || reference.isBlank()) {
            return List.of();
        }
        return datasourceRepository.findAllByCredentialReference(reference).stream()
                .map(DefaultDatasourceLookupService::toDescriptor)
                .toList();
    }

    private static DatasourceConnectionDescriptor toDescriptor(DatasourceEntity entity) {
        return DatasourceDescriptorMapper.from(entity);
    }
}
