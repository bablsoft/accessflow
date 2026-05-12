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
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface QueryRequestRepository
        extends JpaRepository<QueryRequestEntity, UUID>,
                JpaSpecificationExecutor<QueryRequestEntity> {

    Page<QueryRequestEntity> findAllByDatasource_Id(UUID datasourceId, Pageable pageable);

    Page<QueryRequestEntity> findAllBySubmittedBy_Id(UUID userId, Pageable pageable);

    Page<QueryRequestEntity> findAllByStatus(QueryStatus status, Pageable pageable);

    List<QueryRequestEntity> findAllByStatus(QueryStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select q from QueryRequestEntity q where q.id = :id")
    Optional<QueryRequestEntity> findByIdForUpdate(@Param("id") UUID id);

    @Query("""
            select distinct q from QueryRequestEntity q
              join q.datasource d
              join d.reviewPlan rp
              join ReviewPlanApproverEntity rpa on rpa.reviewPlan = rp
            where q.status = :status
              and d.organization.id = :orgId
              and q.submittedBy.id <> :userId
              and (rpa.user.id = :userId or rpa.role = :role)
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
}
