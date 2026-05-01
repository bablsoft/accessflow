package com.partqam.accessflow.core;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface DatasourceRepository extends JpaRepository<Datasource, UUID> {

    List<Datasource> findAllByOrganizationId(UUID organizationId);

    List<Datasource> findAllByOrganizationIdAndActiveTrue(UUID organizationId);
}
