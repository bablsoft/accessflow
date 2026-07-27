package com.bablsoft.accessflow.discovery.internal;

import com.bablsoft.accessflow.discovery.api.DiscoveryFindingView;
import com.bablsoft.accessflow.discovery.api.DiscoveryScanConfigView;
import com.bablsoft.accessflow.discovery.internal.persistence.entity.DiscoveryFindingEntity;
import com.bablsoft.accessflow.discovery.internal.persistence.entity.DiscoveryScanConfigEntity;

final class DiscoveryViewMapper {

    private DiscoveryViewMapper() {
    }

    static DiscoveryScanConfigView toView(DiscoveryScanConfigEntity entity) {
        return new DiscoveryScanConfigView(entity.getDatasourceId(), entity.isEnabled(),
                entity.getSampleSize(), entity.getScanIntervalHours(),
                entity.isAiClassificationEnabled(), entity.getLastScanAt(),
                entity.getLastScanError());
    }

    static DiscoveryFindingView toView(DiscoveryFindingEntity entity) {
        return new DiscoveryFindingView(entity.getId(), entity.getSchemaName(),
                entity.getTableName(), entity.getColumnName(), entity.getClassification(),
                entity.getDetector(), entity.getConfidence(), entity.getSampleRedacted(),
                entity.getRationale(), entity.getMatchCount(), entity.getSampleCount(),
                entity.getStatus(), entity.getFirstDetectedAt(), entity.getLastDetectedAt(),
                entity.getDecidedBy(), entity.getDecidedAt());
    }
}
