package com.bablsoft.accessflow.schemachange.internal.persistence.repo;

import com.bablsoft.accessflow.schemachange.internal.persistence.entity.SchemaChangeSetStatementEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface SchemaChangeSetStatementRepository extends JpaRepository<SchemaChangeSetStatementEntity, UUID> {

    List<SchemaChangeSetStatementEntity> findAllByChangeSet_IdOrderBySequenceOrderAsc(UUID changeSetId);

    /** One batch load for a listed page; the caller groups by change set. */
    List<SchemaChangeSetStatementEntity> findAllByChangeSet_IdInOrderBySequenceOrderAsc(Collection<UUID> changeSetIds);

    /**
     * Bulk delete that runs immediately, so a delete-then-reinsert of the same
     * {@code sequence_order} values in one transaction never trips the unique constraint (a derived
     * entity delete would be queued behind the new INSERTs at flush time).
     */
    @Modifying
    @Query("delete from SchemaChangeSetStatementEntity s where s.changeSet.id = :changeSetId")
    int deleteAllByChangeSetId(@Param("changeSetId") UUID changeSetId);
}
