package com.bablsoft.accessflow.discovery.internal;

import com.bablsoft.accessflow.core.api.DatasourceAdminService;
import com.bablsoft.accessflow.discovery.api.DiscoveryConfigService;
import com.bablsoft.accessflow.discovery.api.DiscoveryScanConfigView;
import com.bablsoft.accessflow.discovery.api.UpsertDiscoveryConfigCommand;
import com.bablsoft.accessflow.discovery.internal.persistence.entity.DiscoveryScanConfigEntity;
import com.bablsoft.accessflow.discovery.internal.persistence.repo.DiscoveryScanConfigRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
class DefaultDiscoveryConfigService implements DiscoveryConfigService {

    private final DiscoveryScanConfigRepository configRepository;
    private final DatasourceAdminService datasourceAdminService;

    @Override
    @Transactional(readOnly = true)
    public DiscoveryScanConfigView get(UUID datasourceId, UUID organizationId) {
        requireDatasource(datasourceId, organizationId);
        return configRepository.findByDatasourceIdAndOrganizationId(datasourceId, organizationId)
                .map(DiscoveryViewMapper::toView)
                .orElseGet(() -> defaultView(datasourceId));
    }

    @Override
    @Transactional
    public DiscoveryScanConfigView upsert(UUID datasourceId, UUID organizationId,
                                          UpsertDiscoveryConfigCommand command) {
        requireDatasource(datasourceId, organizationId);
        var config = configRepository.findByDatasourceIdAndOrganizationId(datasourceId,
                organizationId).orElseGet(() -> {
                    var created = new DiscoveryScanConfigEntity();
                    created.setId(UUID.randomUUID());
                    created.setOrganizationId(organizationId);
                    created.setDatasourceId(datasourceId);
                    return created;
                });
        if (command.enabled() != null) {
            config.setEnabled(command.enabled());
        }
        if (command.sampleSize() != null) {
            config.setSampleSize(command.sampleSize());
        }
        if (command.scanIntervalHours() != null) {
            config.setScanIntervalHours(command.scanIntervalHours());
        }
        if (command.aiClassificationEnabled() != null) {
            config.setAiClassificationEnabled(command.aiClassificationEnabled());
        }
        return DiscoveryViewMapper.toView(configRepository.save(config));
    }

    /** 404 (DatasourceNotFoundException) when the datasource is not in the caller's org. */
    private void requireDatasource(UUID datasourceId, UUID organizationId) {
        datasourceAdminService.getForAdmin(datasourceId, organizationId);
    }

    private static DiscoveryScanConfigView defaultView(UUID datasourceId) {
        return new DiscoveryScanConfigView(datasourceId, false, 100, 24, false, null, null);
    }
}
