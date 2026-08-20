package com.bablsoft.accessflow.audit.internal.persistence.repo;

import com.bablsoft.accessflow.audit.internal.persistence.entity.AuditLogEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AuditLogRepository
        extends JpaRepository<AuditLogEntity, UUID>, JpaSpecificationExecutor<AuditLogEntity> {

    Optional<AuditLogEntity> findTopByOrganizationIdOrderByCreatedAtDescIdDesc(UUID organizationId);

    @Query("select distinct a.organizationId from AuditLogEntity a order by a.organizationId")
    List<UUID> findDistinctOrganizationIds();

    default List<AuditLogEntity> findForVerification(Specification<AuditLogEntity> spec) {
        return findAll(spec, Sort.by(Sort.Order.asc("createdAt"), Sort.Order.asc("id")));
    }

    /**
     * Rows for one organization strictly after the {@code (afterCreatedAt, afterId)} keyset,
     * oldest first — the audit-sink drain read (#628). Org-scoped so it rides
     * {@code idx_audit_log_org_created_id}; {@code Pageable} supplies the batch cap. The
     * {@code id} tiebreaker is load-bearing in both predicate and ordering: {@code created_at}
     * is not unique, so without it resuming from the last row of a full batch either loses the
     * rest of that instant or replays it forever (same shape as
     * {@link GrantUsageAuditRepository#findUsageEvents}).
     */
    @Query("select a from AuditLogEntity a "
            + "where a.organizationId = :orgId "
            + "and (a.createdAt > :afterCreatedAt "
            + "     or (a.createdAt = :afterCreatedAt and a.id > :afterId)) "
            + "order by a.createdAt asc, a.id asc")
    List<AuditLogEntity> findAfterKeyset(@Param("orgId") UUID orgId,
                                         @Param("afterCreatedAt") Instant afterCreatedAt,
                                         @Param("afterId") UUID afterId,
                                         Pageable pageable);

    /**
     * Capped backlog count behind a sink cursor: how many rows exist past the keyset, counting
     * at most the {@code Pageable} cap. Selecting only {@code id} keeps the read index-only.
     */
    @Query("select a.id from AuditLogEntity a "
            + "where a.organizationId = :orgId "
            + "and (a.createdAt > :afterCreatedAt "
            + "     or (a.createdAt = :afterCreatedAt and a.id > :afterId)) "
            + "order by a.createdAt asc, a.id asc")
    List<UUID> findIdsAfterKeyset(@Param("orgId") UUID orgId,
                                  @Param("afterCreatedAt") Instant afterCreatedAt,
                                  @Param("afterId") UUID afterId,
                                  Pageable pageable);
}
