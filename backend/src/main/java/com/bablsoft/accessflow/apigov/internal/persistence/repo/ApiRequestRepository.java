package com.bablsoft.accessflow.apigov.internal.persistence.repo;

import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiRequestEntity;
import com.bablsoft.accessflow.core.api.QueryStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ApiRequestRepository extends JpaRepository<ApiRequestEntity, UUID> {

    Optional<ApiRequestEntity> findByIdAndOrganizationId(UUID id, UUID organizationId);

    Page<ApiRequestEntity> findByOrganizationId(UUID organizationId, Pageable pageable);

    Page<ApiRequestEntity> findByOrganizationIdAndSubmittedBy(UUID organizationId, UUID submittedBy,
                                                             Pageable pageable);

    Page<ApiRequestEntity> findByOrganizationIdAndStatus(UUID organizationId, QueryStatus status,
                                                        Pageable pageable);

    @Query("select r.id from ApiRequestEntity r where r.status = com.bablsoft.accessflow.core.api.QueryStatus.APPROVED "
            + "and r.scheduledFor is not null and r.scheduledFor <= :now")
    List<UUID> findScheduledDueIds(@Param("now") Instant now);

    @Query("select r.id from ApiRequestEntity r where r.status = com.bablsoft.accessflow.core.api.QueryStatus.PENDING_REVIEW "
            + "and r.createdAt <= :cutoff")
    List<UUID> findStalePendingReviewIds(@Param("cutoff") Instant cutoff);
}
