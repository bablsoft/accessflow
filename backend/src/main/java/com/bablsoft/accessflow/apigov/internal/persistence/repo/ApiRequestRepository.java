package com.bablsoft.accessflow.apigov.internal.persistence.repo;

import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiRequestEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ApiRequestRepository extends JpaRepository<ApiRequestEntity, UUID>,
        JpaSpecificationExecutor<ApiRequestEntity> {

    Optional<ApiRequestEntity> findByIdAndOrganizationId(UUID id, UUID organizationId);

    // #622: the escalation scan and a human reviewer can touch the same row in the same instant.
    // Taking the row lock makes the loser wait rather than fail — without it the loser is whichever
    // writer commits second, which can be the human, and @Version surfaces that as a raw 500.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ApiRequestEntity r where r.id = :id")
    Optional<ApiRequestEntity> findByIdForUpdate(@Param("id") UUID id);

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

    /** API requests past their connector plan's escalation window, not yet escalated (#622). */
    @Query(value = """
            SELECT r.id
            FROM api_requests r
            JOIN api_connectors c ON c.id = r.connector_id
            JOIN review_plans rp ON rp.id = c.review_plan_id
            WHERE r.status = 'PENDING_REVIEW'::query_status
              AND r.escalated_at IS NULL
              AND rp.escalation_after_hours IS NOT NULL
              AND r.created_at + (rp.escalation_after_hours || ' hours')::interval < :now
            """, nativeQuery = true)
    List<UUID> findEscalationDueIds(@Param("now") Instant now);

    /** API requests due a reminder on their connector plan's nudge cadence (#622). */
    @Query(value = """
            SELECT r.id
            FROM api_requests r
            JOIN api_connectors c ON c.id = r.connector_id
            JOIN review_plans rp ON rp.id = c.review_plan_id
            WHERE r.status = 'PENDING_REVIEW'::query_status
              AND rp.nudge_interval_hours IS NOT NULL
              AND COALESCE(r.last_nudged_at, r.created_at)
                  + (rp.nudge_interval_hours || ' hours')::interval < :now
            """, nativeQuery = true)
    List<UUID> findNudgeDueIds(@Param("now") Instant now);
}
