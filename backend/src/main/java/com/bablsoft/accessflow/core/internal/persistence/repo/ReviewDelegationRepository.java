package com.bablsoft.accessflow.core.internal.persistence.repo;

import com.bablsoft.accessflow.core.internal.persistence.entity.ReviewDelegationEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReviewDelegationRepository extends JpaRepository<ReviewDelegationEntity, UUID> {

    Optional<ReviewDelegationEntity> findByIdAndOrganizationId(UUID id, UUID organizationId);

    List<ReviewDelegationEntity> findByOrganizationIdAndDelegatorIdOrderByCreatedAtDesc(
            UUID organizationId, UUID delegatorId);

    List<ReviewDelegationEntity> findByOrganizationIdAndDelegateIdOrderByCreatedAtDesc(
            UUID organizationId, UUID delegateId);

    /**
     * Delegations the acting user may currently borrow. The window is half-open
     * {@code [starts_at, ends_at)} and evaluated against the caller-supplied instant, which comes
     * from the injected {@code Clock} bean — never {@code current_timestamp}, which would be a
     * second, unmockable clock source that drifts from the application's.
     *
     * <p>Both parties must be active: an inactive delegator could not review either, so their
     * identity must not be borrowable, and an inactive delegate is filtered defensively. This is a
     * read-time filter rather than a deactivation listener precisely so it cannot drift.
     *
     * <p>Ordered by (created_at, id) so a replayed decision records the same provenance.
     */
    @Query("""
            select d from ReviewDelegationEntity d
              join UserEntity delegator on delegator.id = d.delegatorId
              join UserEntity delegate on delegate.id = d.delegateId
            where d.organizationId = :orgId
              and d.delegateId = :delegateId
              and d.revokedAt is null
              and d.startsAt <= :at
              and d.endsAt > :at
              and delegator.active = true
              and delegate.active = true
            order by d.createdAt asc, d.id asc
            """)
    List<ReviewDelegationEntity> findActiveForDelegate(@Param("orgId") UUID organizationId,
                                                       @Param("delegateId") UUID delegateId,
                                                       @Param("at") Instant at);

    /** Reverse direction of {@link #findActiveForDelegate} — who is currently covering this user. */
    @Query("""
            select d.delegateId from ReviewDelegationEntity d
              join UserEntity delegator on delegator.id = d.delegatorId
              join UserEntity delegate on delegate.id = d.delegateId
            where d.organizationId = :orgId
              and d.delegatorId = :delegatorId
              and d.revokedAt is null
              and d.startsAt <= :at
              and d.endsAt > :at
              and delegator.active = true
              and delegate.active = true
            order by d.createdAt asc
            """)
    List<UUID> findActiveDelegateIds(@Param("orgId") UUID organizationId,
                                     @Param("delegatorId") UUID delegatorId,
                                     @Param("at") Instant at);

    /** Cap check: how many delegations this user currently has open. */
    @Query("""
            select count(d) from ReviewDelegationEntity d
            where d.organizationId = :orgId
              and d.delegatorId = :delegatorId
              and d.revokedAt is null
              and d.endsAt > :at
            """)
    long countOpenForDelegator(@Param("orgId") UUID organizationId,
                               @Param("delegatorId") UUID delegatorId,
                               @Param("at") Instant at);

    @Query("""
            select d from ReviewDelegationEntity d
            where d.organizationId = :orgId
              and (:delegatorId is null or d.delegatorId = :delegatorId)
              and (:delegateId is null or d.delegateId = :delegateId)
              and (:activeOnly = false
                   or (d.revokedAt is null and d.startsAt <= :at and d.endsAt > :at))
            order by d.createdAt desc
            """)
    Page<ReviewDelegationEntity> search(@Param("orgId") UUID organizationId,
                                        @Param("delegatorId") UUID delegatorId,
                                        @Param("delegateId") UUID delegateId,
                                        @Param("activeOnly") boolean activeOnly,
                                        @Param("at") Instant at,
                                        Pageable pageable);
}
