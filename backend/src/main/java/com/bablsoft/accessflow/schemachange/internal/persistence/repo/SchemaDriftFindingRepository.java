package com.bablsoft.accessflow.schemachange.internal.persistence.repo;

import com.bablsoft.accessflow.schemachange.api.SchemaDriftFindingKind;
import com.bablsoft.accessflow.schemachange.api.SchemaDriftFindingStatus;
import com.bablsoft.accessflow.schemachange.internal.persistence.entity.SchemaDriftFindingEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SchemaDriftFindingRepository extends JpaRepository<SchemaDriftFindingEntity, UUID>,
        JpaSpecificationExecutor<SchemaDriftFindingEntity> {

    Optional<SchemaDriftFindingEntity> findByIdAndOrganizationId(UUID id, UUID organizationId);

    List<SchemaDriftFindingEntity> findAllByScan_IdOrderByObjectPathAsc(UUID scanId);

    /** The findings a scan owns once it finishes — its {@code findings_count}, read rather than tallied. */
    long countByScan_Id(UUID scanId);

    /**
     * The active worklist for one environment. Reconciliation loads this once per scan; the resolved
     * rows are deliberately left out, since they accumulate without bound and are only ever looked up
     * one path at a time when a finding reappears.
     */
    List<SchemaDriftFindingEntity> findAllByOrganizationIdAndEnvironmentIdAndStatusIn(
            UUID organizationId, UUID environmentId, Collection<SchemaDriftFindingStatus> statuses);

    /** The natural key: one finding per object path per kind per environment. */
    Optional<SchemaDriftFindingEntity> findFirstByOrganizationIdAndEnvironmentIdAndObjectPathAndFindingKindOrderByLastSeenAtDesc(
            UUID organizationId, UUID environmentId, String objectPath, SchemaDriftFindingKind findingKind);
}
