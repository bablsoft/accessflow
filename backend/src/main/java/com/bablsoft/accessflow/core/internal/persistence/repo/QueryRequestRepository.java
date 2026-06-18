package com.bablsoft.accessflow.core.internal.persistence.repo;

import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.internal.persistence.entity.QueryRequestEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface QueryRequestRepository
        extends JpaRepository<QueryRequestEntity, UUID>,
                JpaSpecificationExecutor<QueryRequestEntity>,
                QueryRequestStatsRepository {

    Page<QueryRequestEntity> findAllByDatasource_Id(UUID datasourceId, Pageable pageable);

    Page<QueryRequestEntity> findAllBySubmittedBy_Id(UUID userId, Pageable pageable);

    Page<QueryRequestEntity> findAllByStatus(QueryStatus status, Pageable pageable);

    List<QueryRequestEntity> findAllByStatus(QueryStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select q from QueryRequestEntity q where q.id = :id")
    Optional<QueryRequestEntity> findByIdForUpdate(@Param("id") UUID id);

    // AF-456: rolling-window query count for an org's per-day quota. Scoped through the datasource's
    // organization since query_requests has no direct org FK.
    @Query("""
            select count(q) from QueryRequestEntity q
             where q.datasource.organization.id = :orgId
               and q.createdAt >= :since
            """)
    long countByOrganizationSince(@Param("orgId") UUID organizationId, @Param("since") Instant since);

    @Query("""
            select distinct q from QueryRequestEntity q
              join q.datasource d
              join d.reviewPlan rp
              join ReviewPlanApproverEntity rpa on rpa.reviewPlan = rp
            where q.status = :status
              and d.organization.id = :orgId
              and q.submittedBy.id <> :userId
              and (rpa.user.id = :userId or rpa.role = :role)
              and (
                not exists (select 1 from DatasourceReviewerEntity dr where dr.datasource = d)
                or exists (
                  select 1 from DatasourceReviewerEntity dr
                   where dr.datasource = d and dr.user.id = :userId
                )
                or exists (
                  select 1 from DatasourceReviewerEntity dr
                    join UserGroupMembershipEntity m on m.group = dr.group
                   where dr.datasource = d and m.user.id = :userId
                )
              )
            """)
    Page<QueryRequestEntity> findPendingForReviewer(@Param("orgId") UUID orgId,
                                                    @Param("userId") UUID userId,
                                                    @Param("role") UserRoleType role,
                                                    @Param("status") QueryStatus status,
                                                    Pageable pageable);

    @Query(value = """
            SELECT q.id
            FROM query_requests q
            JOIN datasources d ON q.datasource_id = d.id
            JOIN review_plans rp ON d.review_plan_id = rp.id
            WHERE q.status = 'PENDING_REVIEW'::query_status
              AND q.created_at + (rp.approval_timeout_hours || ' hours')::interval < :now
            """, nativeQuery = true)
    List<UUID> findTimedOutPendingReviewIds(@Param("now") Instant now);

    @Query(value = """
            SELECT q.id
            FROM query_requests q
            WHERE q.status = 'APPROVED'::query_status
              AND q.scheduled_for IS NOT NULL
              AND q.scheduled_for <= :now
            """, nativeQuery = true)
    List<UUID> findScheduledDueIds(@Param("now") Instant now);

    @Query("""
            select q.id from QueryRequestEntity q
             where q.status = :status
               and q.submittedBy.id = :submitterId
               and q.datasource.id = :datasourceId
               and q.canonicalSql = :canonicalSql
               and q.id <> :excludeId
             order by q.executionCompletedAt desc
            """)
    List<UUID> findPreviousExecutedRunIds(@Param("status") QueryStatus status,
                                          @Param("submitterId") UUID submitterId,
                                          @Param("datasourceId") UUID datasourceId,
                                          @Param("canonicalSql") String canonicalSql,
                                          @Param("excludeId") UUID excludeId,
                                          Pageable pageable);

    // AF-446: most recent approval-time of the requester's prior queries on the same datasource
    // (status APPROVED or EXECUTED), used by the time-since-last-approval routing condition.
    // updatedAt is the @Version column, which equals the approve / execute transition time.
    @Query("""
            select max(q.updatedAt) from QueryRequestEntity q
             where q.datasource.organization.id = :orgId
               and q.submittedBy.id = :userId
               and q.datasource.id = :datasourceId
               and q.status in :statuses
               and (:excludingQueryId is null or q.id <> :excludingQueryId)
            """)
    Optional<Instant> findLastApprovalInstant(@Param("orgId") UUID organizationId,
                                              @Param("userId") UUID userId,
                                              @Param("datasourceId") UUID datasourceId,
                                              @Param("statuses") Collection<QueryStatus> statuses,
                                              @Param("excludingQueryId") UUID excludingQueryId);
}
