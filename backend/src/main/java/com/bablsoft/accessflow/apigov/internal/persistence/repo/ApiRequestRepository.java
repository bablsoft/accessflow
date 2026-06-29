package com.bablsoft.accessflow.apigov.internal.persistence.repo;

import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiRequestEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ApiRequestRepository extends JpaRepository<ApiRequestEntity, UUID>,
        JpaSpecificationExecutor<ApiRequestEntity> {

    Optional<ApiRequestEntity> findByIdAndOrganizationId(UUID id, UUID organizationId);

    // Native queries with an explicit ::query_status cast — the same pattern QueryRequestRepository
    // uses for its scheduled/timeout scans. A JPQL enum literal makes Hibernate emit a cast to a
    // non-existent "querystatus" type.
    @Query(value = """
            SELECT id FROM api_requests
            WHERE status = 'APPROVED'::query_status
              AND scheduled_for IS NOT NULL
              AND scheduled_for <= :now
            """, nativeQuery = true)
    List<UUID> findScheduledDueIds(@Param("now") Instant now);

    @Query(value = """
            SELECT id FROM api_requests
            WHERE status = 'PENDING_REVIEW'::query_status
              AND created_at <= :cutoff
            """, nativeQuery = true)
    List<UUID> findStalePendingReviewIds(@Param("cutoff") Instant cutoff);
}
