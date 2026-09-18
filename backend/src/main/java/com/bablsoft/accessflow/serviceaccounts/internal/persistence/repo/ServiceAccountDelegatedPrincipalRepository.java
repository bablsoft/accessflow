package com.bablsoft.accessflow.serviceaccounts.internal.persistence.repo;

import com.bablsoft.accessflow.serviceaccounts.internal.persistence.entity.ServiceAccountDelegatedPrincipalEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ServiceAccountDelegatedPrincipalRepository
        extends JpaRepository<ServiceAccountDelegatedPrincipalEntity, UUID> {

    List<ServiceAccountDelegatedPrincipalEntity> findAllByServiceAccountUserIdAndOrganizationIdOrderByCreatedAtDesc(
            UUID serviceAccountUserId, UUID organizationId);

    List<ServiceAccountDelegatedPrincipalEntity> findAllByPrincipalUserIdAndOrganizationIdOrderByCreatedAtDesc(
            UUID principalUserId, UUID organizationId);

    Optional<ServiceAccountDelegatedPrincipalEntity> findByIdAndOrganizationId(UUID id, UUID organizationId);

    /** The one unrevoked row for the pair (the partial unique index guarantees at most one). */
    Optional<ServiceAccountDelegatedPrincipalEntity> findByServiceAccountUserIdAndPrincipalUserIdAndRevokedAtIsNull(
            UUID serviceAccountUserId, UUID principalUserId);

    /** The header hot path: unrevoked and not expired at {@code now}. */
    @Query("""
            select d from ServiceAccountDelegatedPrincipalEntity d
            where d.serviceAccountUserId = :serviceAccountUserId
              and d.principalUserId = :principalUserId
              and d.revokedAt is null
              and (d.expiresAt is null or d.expiresAt > :now)
            """)
    Optional<ServiceAccountDelegatedPrincipalEntity> findLive(@Param("serviceAccountUserId") UUID serviceAccountUserId,
                                                             @Param("principalUserId") UUID principalUserId,
                                                             @Param("now") Instant now);
}
