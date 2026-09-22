package com.bablsoft.accessflow.schemachange.internal.persistence.repo;

import com.bablsoft.accessflow.schemachange.internal.persistence.entity.SchemaDriftScanEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SchemaDriftScanRepository extends JpaRepository<SchemaDriftScanEntity, UUID> {

    List<SchemaDriftScanEntity> findAllByOrganizationIdAndEnvironmentIdOrderByStartedAtDesc(UUID organizationId,
                                                                                            UUID environmentId);
}
