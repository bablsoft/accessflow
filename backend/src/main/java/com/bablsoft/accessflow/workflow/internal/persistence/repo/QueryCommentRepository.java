package com.bablsoft.accessflow.workflow.internal.persistence.repo;

import com.bablsoft.accessflow.workflow.internal.persistence.entity.QueryCommentEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface QueryCommentRepository extends JpaRepository<QueryCommentEntity, UUID> {

    /**
     * All comments (roots + replies) for a query, oldest first, so the service can assemble
     * threads in a single pass.
     */
    List<QueryCommentEntity> findByQueryRequestIdOrderByCreatedAtAsc(UUID queryRequestId);

    Optional<QueryCommentEntity> findByIdAndQueryRequestId(UUID id, UUID queryRequestId);
}
