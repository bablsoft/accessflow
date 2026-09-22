package com.bablsoft.accessflow.schemachange.internal.persistence.repo;

import com.bablsoft.accessflow.schemachange.internal.persistence.entity.SchemaChangeSetStatementEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SchemaChangeSetStatementRepository extends JpaRepository<SchemaChangeSetStatementEntity, UUID> {

    List<SchemaChangeSetStatementEntity> findAllByChangeSet_IdOrderBySequenceOrderAsc(UUID changeSetId);

    void deleteAllByChangeSet_Id(UUID changeSetId);
}
