package com.bablsoft.accessflow.schemachange.internal.persistence.repo;

import com.bablsoft.accessflow.schemachange.internal.persistence.entity.SchemaDriftConfigEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SchemaDriftConfigRepository extends JpaRepository<SchemaDriftConfigEntity, UUID> {

    /** The drift job's drain query — every pipeline that opted in, across organizations. */
    List<SchemaDriftConfigEntity> findAllByEnabledTrue();

    Optional<SchemaDriftConfigEntity> findByPipelineIdAndOrganizationId(UUID pipelineId, UUID organizationId);

    List<SchemaDriftConfigEntity> findAllByOrganizationIdOrderByPipelineIdAsc(UUID organizationId);
}
