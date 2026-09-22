package com.bablsoft.accessflow.schemachange.internal.persistence.repo;

import com.bablsoft.accessflow.schemachange.api.SchemaChangePromotionStatus;
import com.bablsoft.accessflow.schemachange.internal.persistence.entity.SchemaChangeSetPromotionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SchemaChangeSetPromotionRepository extends JpaRepository<SchemaChangeSetPromotionEntity, UUID> {

    Optional<SchemaChangeSetPromotionEntity> findByIdAndOrganizationId(UUID id, UUID organizationId);

    List<SchemaChangeSetPromotionEntity> findAllByChangeSet_IdOrderBySubmittedAtDesc(UUID changeSetId);

    /** The freeze probe (#879): does any promotion of the set sit in one of the given states? */
    boolean existsByChangeSet_IdAndStatusIn(UUID changeSetId, Collection<SchemaChangePromotionStatus> statuses);

    /** The promotion a request group was created for, if any — the status-projection lookup. */
    Optional<SchemaChangeSetPromotionEntity> findByRequestGroupId(UUID requestGroupId);
}
