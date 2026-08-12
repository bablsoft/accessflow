package com.bablsoft.accessflow.audit.internal.persistence.repo;

import com.bablsoft.accessflow.audit.internal.persistence.entity.AuditLogEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Read-only projection over {@code audit_log} for grant-usage aggregation (#625). Kept separate from
 * {@link AuditLogRepository} for the same reason as {@link BehaviorAuditRepository}: the aggregation
 * queries reach into the JSONB {@code metadata}, so they belong with the table that owns the schema.
 */
public interface GrantUsageAuditRepository extends Repository<AuditLogEntity, UUID> {

    /**
     * Execution events for one organization in {@code [from, to)}, oldest first. Org-scoped so the
     * read rides {@code idx_audit_log_created}; {@code Pageable} supplies the row cap. JSONB
     * metadata is parsed in the service, matching the UBA reader.
     */
    @Query("select a from AuditLogEntity a "
            + "where a.organizationId = :orgId and a.action in :actions and a.actorId is not null "
            + "and a.createdAt >= :from and a.createdAt < :to "
            + "order by a.createdAt asc")
    List<AuditLogEntity> findUsageEvents(@Param("orgId") UUID orgId,
                                         @Param("actions") Collection<String> actions,
                                         @Param("from") Instant from,
                                         @Param("to") Instant to,
                                         Pageable pageable);
}
