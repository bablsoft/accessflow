package com.bablsoft.accessflow.requestgroups.internal.persistence.repo;

import com.bablsoft.accessflow.requestgroups.internal.persistence.entity.RequestGroupEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RequestGroupRepository extends JpaRepository<RequestGroupEntity, UUID>,
        JpaSpecificationExecutor<RequestGroupEntity> {

    Optional<RequestGroupEntity> findByIdAndOrganizationId(UUID id, UUID organizationId);

    // Native queries with an explicit ::request_group_status cast — the same pattern
    // ApiRequestRepository / QueryRequestRepository use for their scheduled/timeout scans.
    @Query(value = """
            SELECT id FROM request_groups
            WHERE status = 'APPROVED'::request_group_status
              AND scheduled_for IS NOT NULL
              AND scheduled_for <= :now
            """, nativeQuery = true)
    List<UUID> findScheduledDueIds(@Param("now") Instant now);

    @Query(value = """
            SELECT id FROM request_groups
            WHERE status = 'PENDING_REVIEW'::request_group_status
              AND created_at <= :cutoff
            """, nativeQuery = true)
    List<UUID> findStalePendingReviewIds(@Param("cutoff") Instant cutoff);
}
