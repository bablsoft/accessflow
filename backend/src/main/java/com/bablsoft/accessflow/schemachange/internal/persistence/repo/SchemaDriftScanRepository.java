package com.bablsoft.accessflow.schemachange.internal.persistence.repo;

import com.bablsoft.accessflow.schemachange.internal.persistence.entity.SchemaDriftScanEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface SchemaDriftScanRepository extends JpaRepository<SchemaDriftScanEntity, UUID>,
        JpaSpecificationExecutor<SchemaDriftScanEntity> {

    List<SchemaDriftScanEntity> findAllByOrganizationIdAndEnvironmentIdOrderByStartedAtDesc(UUID organizationId,
                                                                                            UUID environmentId);

    /**
     * Whether a scan of this environment is still running. Deliberately bounded by {@code startedAfter}:
     * a scan row orphaned by a replica that died mid-run would otherwise make the environment answer
     * 409 forever, outliving the cluster lock it shadows. Callers pass
     * {@code now - drift-scan-lock-at-most-for}, the lock's own ceiling.
     */
    boolean existsByOrganizationIdAndEnvironmentIdAndFinishedAtIsNullAndStartedAtAfter(UUID organizationId,
                                                                                       UUID environmentId,
                                                                                       Instant startedAfter);
}
