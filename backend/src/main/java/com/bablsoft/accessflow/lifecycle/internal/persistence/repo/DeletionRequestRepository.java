package com.bablsoft.accessflow.lifecycle.internal.persistence.repo;

import com.bablsoft.accessflow.lifecycle.api.ErasureStatus;
import com.bablsoft.accessflow.lifecycle.internal.persistence.entity.DeletionRequestEntity;
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

public interface DeletionRequestRepository extends JpaRepository<DeletionRequestEntity, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from DeletionRequestEntity d where d.id = :id")
    Optional<DeletionRequestEntity> findByIdForUpdate(@Param("id") UUID id);

    Optional<DeletionRequestEntity> findByIdAndOrganizationId(UUID id, UUID organizationId);

    Page<DeletionRequestEntity> findAllByOrganizationIdAndStatus(
            UUID organizationId, ErasureStatus status, Pageable pageable);

    Page<DeletionRequestEntity> findAllByOrganizationIdAndStatusAndRequestedByNot(
            UUID organizationId, ErasureStatus status, UUID requestedBy, Pageable pageable);

    Page<DeletionRequestEntity> findAllByOrganizationIdAndRequestedBy(
            UUID organizationId, UUID requestedBy, Pageable pageable);

    List<DeletionRequestEntity> findAllByOrganizationIdAndStatusOrderByCreatedAtAsc(
            UUID organizationId, ErasureStatus status);

    @Query("select d.id from DeletionRequestEntity d where d.status = :status")
    List<UUID> findIdsByStatus(@Param("status") ErasureStatus status);

    // Bind the status as a parameter — an inlined JPQL enum literal is rendered with a cast to the
    // Java class name (::ErasureStatus), not the Postgres enum type erasure_status (#546).
    @Query("select d.id from DeletionRequestEntity d where d.status = :status and d.updatedAt < :cutoff")
    List<UUID> findTimedOutPendingReviewIds(@Param("status") ErasureStatus status,
                                            @Param("cutoff") java.time.Instant cutoff);
}
