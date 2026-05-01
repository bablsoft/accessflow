package com.partqam.accessflow.core.internal.persistence.repo;

import com.partqam.accessflow.core.internal.persistence.entity.DatasourceEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface DatasourceRepository extends JpaRepository<DatasourceEntity, UUID> {

    List<DatasourceEntity> findAllByOrganization_Id(UUID organizationId);

    List<DatasourceEntity> findAllByOrganization_IdAndActiveTrue(UUID organizationId);
}
