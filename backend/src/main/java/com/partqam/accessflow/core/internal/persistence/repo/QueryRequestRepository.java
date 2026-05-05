package com.partqam.accessflow.core.internal.persistence.repo;

import com.partqam.accessflow.core.api.QueryStatus;
import com.partqam.accessflow.core.api.UserRoleType;
import com.partqam.accessflow.core.internal.persistence.entity.QueryRequestEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface QueryRequestRepository extends JpaRepository<QueryRequestEntity, UUID> {

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
            where q.status = com.partqam.accessflow.core.api.QueryStatus.PENDING_REVIEW
              and d.organization.id = :orgId
              and q.submittedBy.id <> :userId
              and (rpa.user.id = :userId or rpa.role = :role)
            """)
    Page<QueryRequestEntity> findPendingForReviewer(@Param("orgId") UUID orgId,
                                                    @Param("userId") UUID userId,
                                                    @Param("role") UserRoleType role,
                                                    Pageable pageable);
}
