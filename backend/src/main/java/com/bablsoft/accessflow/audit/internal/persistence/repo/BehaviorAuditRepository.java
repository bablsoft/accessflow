package com.bablsoft.accessflow.audit.internal.persistence.repo;

import com.bablsoft.accessflow.audit.internal.persistence.entity.AuditLogEntity;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Read-only projections over {@code audit_log} for behavioural anomaly detection (AF-383). Kept
 * separate from {@link AuditLogRepository} so the UBA aggregation queries (which reach into the
 * JSONB {@code metadata} for the datasource id) live with the table that owns the schema.
 */
public interface BehaviorAuditRepository extends Repository<AuditLogEntity, UUID> {

    /** Distinct (org, user, datasource) subjects that ran a query in {@code [from, to)}. */
    @Query(value = """
            SELECT DISTINCT organization_id AS organizationId,
                            actor_id AS userId,
                            (metadata->>'datasource_id')::uuid AS datasourceId
            FROM audit_log
            WHERE action IN ('QUERY_EXECUTED', 'QUERY_FAILED')
              AND actor_id IS NOT NULL
              AND metadata->>'datasource_id' IS NOT NULL
              AND created_at >= :from
              AND created_at < :to
            """, nativeQuery = true)
    List<SubjectProjection> findActiveSubjects(@Param("from") Instant from, @Param("to") Instant to);

    /** Raw query events for one (org, user) in {@code [from, to)}; datasource filtering and JSONB
     *  metadata parsing happen in the service from the entity's {@code metadata} string. */
    List<AuditLogEntity>
        findByOrganizationIdAndActorIdAndActionInAndCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtAsc(
            UUID organizationId, UUID actorId, Collection<String> actions, Instant from, Instant to);

    interface SubjectProjection {
        UUID getOrganizationId();

        UUID getUserId();

        UUID getDatasourceId();
    }
}
