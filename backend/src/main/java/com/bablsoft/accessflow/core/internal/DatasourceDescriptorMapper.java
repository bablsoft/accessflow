package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.core.api.DatasourceConnectionDescriptor;
import com.bablsoft.accessflow.core.internal.persistence.entity.DatasourceEntity;

/**
 * Maps a {@link DatasourceEntity} to the cross-module {@link DatasourceConnectionDescriptor}. Shared
 * by {@code DefaultDatasourceLookupService} (proxy-facing reads) and the MongoDB connection-test /
 * introspection path so the field mapping lives in one place.
 */
final class DatasourceDescriptorMapper {

    private DatasourceDescriptorMapper() {
    }

    static DatasourceConnectionDescriptor from(DatasourceEntity entity) {
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
                entity.isTextToSqlEnabled(),
                entity.getCustomDriver() != null ? entity.getCustomDriver().getId() : null,
                entity.getConnectorId(),
                entity.getJdbcUrlOverride(),
                entity.getReadReplicaJdbcUrl(),
                entity.getReadReplicaUsername(),
                entity.getReadReplicaPasswordEncrypted(),
                entity.isActive());
    }
}
