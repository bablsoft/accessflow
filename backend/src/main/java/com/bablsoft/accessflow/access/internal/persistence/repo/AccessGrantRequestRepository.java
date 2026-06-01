package com.bablsoft.accessflow.access.internal.persistence.repo;

import com.bablsoft.accessflow.access.api.AccessGrantStatus;
import com.bablsoft.accessflow.access.internal.persistence.entity.AccessGrantRequestEntity;
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

public interface AccessGrantRequestRepository
        extends JpaRepository<AccessGrantRequestEntity, UUID>,
                JpaSpecificationExecutor<AccessGrantRequestEntity> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from AccessGrantRequestEntity a where a.id = :id")
    Optional<AccessGrantRequestEntity> findByIdForUpdate(@Param("id") UUID id);

    Page<AccessGrantRequestEntity> findAllByRequesterIdAndOrganizationId(
            UUID requesterId, UUID organizationId, Pageable pageable);

    List<AccessGrantRequestEntity> findAllByOrganizationIdAndStatusOrderByCreatedAtAsc(
            UUID organizationId, AccessGrantStatus status);

    @Query("select a.id from AccessGrantRequestEntity a "
            + "where a.status = :status and a.expiresAt <= :now")
    List<UUID> findIdsByStatusAndExpiresAtBefore(@Param("status") AccessGrantStatus status,
                                                 @Param("now") Instant now);
}
