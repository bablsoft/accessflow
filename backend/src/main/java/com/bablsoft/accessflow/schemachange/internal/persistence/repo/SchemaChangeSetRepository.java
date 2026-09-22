package com.bablsoft.accessflow.schemachange.internal.persistence.repo;

import com.bablsoft.accessflow.schemachange.internal.persistence.entity.SchemaChangeSetEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;
import java.util.UUID;

public interface SchemaChangeSetRepository
        extends JpaRepository<SchemaChangeSetEntity, UUID>, JpaSpecificationExecutor<SchemaChangeSetEntity> {

    Optional<SchemaChangeSetEntity> findByIdAndOrganizationId(UUID id, UUID organizationId);

    boolean existsByOrganizationIdAndPipelineIdAndName(UUID organizationId, UUID pipelineId, String name);
}
