package com.bablsoft.accessflow.schemachange.internal.persistence.repo;

import com.bablsoft.accessflow.schemachange.api.SchemaChangePromotionStatus;
import com.bablsoft.accessflow.schemachange.internal.persistence.entity.SchemaChangeSetPromotionEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /**
     * The same lookup under a row lock (#880): group status events arrive asynchronously and may
     * reorder, so the projection listener and the cancel path serialise on the promotion row
     * instead of losing an optimistic-lock race.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from SchemaChangeSetPromotionEntity p where p.requestGroupId = :requestGroupId")
    Optional<SchemaChangeSetPromotionEntity> findByRequestGroupIdForUpdate(@Param("requestGroupId") UUID requestGroupId);

    /** The same row lock for the cancel path, which races the projection listener on one row. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from SchemaChangeSetPromotionEntity p where p.id = :id and p.organizationId = :organizationId")
    Optional<SchemaChangeSetPromotionEntity> findByIdAndOrganizationIdForUpdate(@Param("id") UUID id,
                                                                                @Param("organizationId") UUID organizationId);

    /** The ladder probe (#880): has the set an {@code APPLIED} promotion on a lower rung? */
    boolean existsByChangeSet_IdAndEnvironmentIdAndStatus(UUID changeSetId, UUID environmentId,
                                                         SchemaChangePromotionStatus status);

    /** The open-promotion pre-check (#880) — the partial unique index catches what this misses. */
    boolean existsByChangeSet_IdAndEnvironmentIdAndStatusIn(UUID changeSetId, UUID environmentId,
                                                           Collection<SchemaChangePromotionStatus> statuses);

    /**
     * The {@code PROMOTION_SNAPSHOT} drift baseline (#881): the most recently applied promotion to this
     * environment, whether or not it carries a snapshot. Deliberately not filtered on a non-null
     * snapshot — falling back to an older promotion's snapshot would report the newest change set's
     * own DDL as drift. A newest promotion without a snapshot is a missing baseline, and says so.
     */
    Optional<SchemaChangeSetPromotionEntity> findFirstByOrganizationIdAndEnvironmentIdAndStatusOrderByAppliedAtDesc(
            UUID organizationId, UUID environmentId, SchemaChangePromotionStatus status);
}
