package com.bablsoft.accessflow.schemachange.internal.persistence.repo;

import com.bablsoft.accessflow.schemachange.internal.persistence.entity.SchemaChangeSetPromotionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SchemaChangeSetPromotionRepository extends JpaRepository<SchemaChangeSetPromotionEntity, UUID> {

    Optional<SchemaChangeSetPromotionEntity> findByIdAndOrganizationId(UUID id, UUID organizationId);

    List<SchemaChangeSetPromotionEntity> findAllByChangeSet_IdOrderBySubmittedAtDesc(UUID changeSetId);

    /** The promotion a request group was created for, if any — the status-projection lookup. */
    Optional<SchemaChangeSetPromotionEntity> findByRequestGroupId(UUID requestGroupId);
}
