package com.partqam.accessflow.core.internal;

import com.partqam.accessflow.core.api.DatasourceConnectionDescriptor;
import com.partqam.accessflow.core.api.DatasourceLookupService;
import com.partqam.accessflow.core.internal.persistence.entity.DatasourceEntity;
import com.partqam.accessflow.core.internal.persistence.repo.DatasourceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
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

    private static DatasourceConnectionDescriptor toDescriptor(DatasourceEntity entity) {
        return new DatasourceConnectionDescriptor(
                entity.getId(),
                entity.getDbType(),
                entity.getHost(),
                entity.getPort(),
                entity.getDatabaseName(),
                entity.getUsername(),
                entity.getPasswordEncrypted(),
                entity.getSslMode(),
                entity.getConnectionPoolSize(),
                entity.getMaxRowsPerQuery(),
                entity.isActive());
    }
}
