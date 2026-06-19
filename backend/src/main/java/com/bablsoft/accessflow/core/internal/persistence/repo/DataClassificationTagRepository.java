package com.bablsoft.accessflow.core.internal.persistence.repo;

import com.bablsoft.accessflow.core.api.DataClassification;
import com.bablsoft.accessflow.core.internal.persistence.entity.DataClassificationTagEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DataClassificationTagRepository
        extends JpaRepository<DataClassificationTagEntity, UUID> {

    List<DataClassificationTagEntity>
        findAllByOrganizationIdAndDatasourceIdOrderByTableNameAscColumnNameAscClassificationAsc(
            UUID organizationId, UUID datasourceId);

    List<DataClassificationTagEntity> findAllByOrganizationIdOrderByCreatedAtAsc(UUID organizationId);

    Optional<DataClassificationTagEntity> findByIdAndOrganizationId(UUID id, UUID organizationId);

    boolean existsByOrganizationIdAndDatasourceIdAndTableNameAndColumnNameAndClassification(
            UUID organizationId, UUID datasourceId, String tableName, String columnName,
            DataClassification classification);
}
