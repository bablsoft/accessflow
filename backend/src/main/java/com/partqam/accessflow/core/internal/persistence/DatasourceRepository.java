package com.partqam.accessflow.core.internal.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface DatasourceRepository extends JpaRepository<Datasource, UUID> {

    List<Datasource> findAllByOrganization_Id(UUID organizationId);

    List<Datasource> findAllByOrganization_IdAndActiveTrue(UUID organizationId);
}
