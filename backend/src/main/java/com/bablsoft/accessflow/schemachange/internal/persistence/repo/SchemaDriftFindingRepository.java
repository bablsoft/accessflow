package com.bablsoft.accessflow.schemachange.internal.persistence.repo;

import com.bablsoft.accessflow.schemachange.internal.persistence.entity.SchemaDriftFindingEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SchemaDriftFindingRepository extends JpaRepository<SchemaDriftFindingEntity, UUID> {

    Optional<SchemaDriftFindingEntity> findByIdAndOrganizationId(UUID id, UUID organizationId);

    List<SchemaDriftFindingEntity> findAllByScan_IdOrderByObjectPathAsc(UUID scanId);
}
