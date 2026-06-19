package com.bablsoft.accessflow.workflow.internal.persistence.repo;

import com.bablsoft.accessflow.workflow.internal.persistence.entity.QuerySnapshotEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface QuerySnapshotRepository extends JpaRepository<QuerySnapshotEntity, UUID> {

    boolean existsByQueryRequestId(UUID queryRequestId);

    Optional<QuerySnapshotEntity> findByQueryRequestId(UUID queryRequestId);

    Optional<QuerySnapshotEntity> findByQueryRequestIdAndOrganizationId(UUID queryRequestId,
                                                                        UUID organizationId);
}
