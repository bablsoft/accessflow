package com.bablsoft.accessflow.core.internal.persistence.repo;

import com.bablsoft.accessflow.core.internal.persistence.entity.QueryRequestEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

// #968: per-submitter usage evidence for the privileged-access report. Postgres-specific (aggregate
// FILTER); AccessFlow is Postgres-only. Org scope is applied through datasources.organization_id,
// mirroring MyQueryInsightsRepository; the submitted_by predicate rides idx_query_requests_submitter (V168).
public interface QuerySubmitterEvidenceRepository extends JpaRepository<QueryRequestEntity, UUID> {

    @Query(value = """
            SELECT qr.submitted_by                                                             AS user_id,
                   COUNT(*)                                                                    AS submitted_query_count,
                   MAX(qr.created_at)                                                          AS last_submitted_at,
                   COUNT(*)           FILTER (WHERE qr.submission_reason = 'EMERGENCY_ACCESS') AS break_glass_execution_count,
                   MAX(qr.created_at) FILTER (WHERE qr.submission_reason = 'EMERGENCY_ACCESS') AS last_break_glass_at
            FROM query_requests qr
            JOIN datasources d ON d.id = qr.datasource_id
            WHERE d.organization_id = :organizationId
              AND qr.submitted_by IN (:userIds)
            GROUP BY qr.submitted_by
            """, nativeQuery = true)
    List<SubmitterEvidenceRow> findBySubmitters(@Param("organizationId") UUID organizationId,
                                                @Param("userIds") Collection<UUID> userIds);

    interface SubmitterEvidenceRow {
        UUID getUserId();
        long getSubmittedQueryCount();
        Instant getLastSubmittedAt();
        long getBreakGlassExecutionCount();
        Instant getLastBreakGlassAt();
    }
}
