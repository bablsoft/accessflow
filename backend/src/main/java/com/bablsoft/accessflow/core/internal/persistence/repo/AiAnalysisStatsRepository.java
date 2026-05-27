package com.bablsoft.accessflow.core.internal.persistence.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.bablsoft.accessflow.core.internal.persistence.entity.AiAnalysisEntity;

// Postgres-specific aggregation queries (jsonb_array_elements, date_trunc, FILTER).
// AccessFlow is Postgres-only — see CLAUDE.md.
public interface AiAnalysisStatsRepository extends JpaRepository<AiAnalysisEntity, UUID> {

    @Query(value = """
            SELECT date_trunc('day', a.created_at)::date AS bucket_date,
                   AVG(a.risk_score) FILTER (WHERE a.failed = false) AS success_avg_risk_score,
                   COUNT(*)                                          AS total_count,
                   COUNT(*) FILTER (WHERE a.failed = false)          AS success_count
            FROM ai_analyses a
            JOIN query_requests qr ON qr.id = a.query_request_id
            JOIN datasources d     ON d.id = qr.datasource_id
            WHERE d.organization_id = :organizationId
              AND a.created_at >= :from
              AND a.created_at <  :to
              AND (CAST(:datasourceId AS uuid) IS NULL
                   OR qr.datasource_id = CAST(:datasourceId AS uuid))
            GROUP BY bucket_date
            ORDER BY bucket_date ASC
            """, nativeQuery = true)
    List<RiskScoreBucketRow> findRiskScoreByDay(@Param("organizationId") UUID organizationId,
                                                @Param("from") Instant from,
                                                @Param("to") Instant to,
                                                @Param("datasourceId") UUID datasourceId);

    // `jsonb_exists(e, 'category')` is used instead of the `e ? 'category'` operator so Hibernate
    // doesn't mistake the JSONB `?` for a positional JDBC parameter ("Mixing of ? parameters
    // and other forms like ?1 is not supported"). Same reason JSON keys that contain colons can
    // never be inlined into Hibernate native SQL.
    @Query(value = """
            SELECT LOWER(TRIM(e->>'category')) AS category,
                   COUNT(*)                    AS cnt
            FROM ai_analyses a
            JOIN query_requests qr ON qr.id = a.query_request_id
            JOIN datasources d     ON d.id = qr.datasource_id
            CROSS JOIN LATERAL jsonb_array_elements(a.issues) e
            WHERE d.organization_id = :organizationId
              AND a.created_at >= :from
              AND a.created_at <  :to
              AND (CAST(:datasourceId AS uuid) IS NULL
                   OR qr.datasource_id = CAST(:datasourceId AS uuid))
              AND jsonb_exists(e, 'category')
              AND LENGTH(TRIM(e->>'category')) > 0
            GROUP BY LOWER(TRIM(e->>'category'))
            ORDER BY cnt DESC
            LIMIT 10
            """, nativeQuery = true)
    List<IssueCategoryRow> findTopIssueCategories(@Param("organizationId") UUID organizationId,
                                                  @Param("from") Instant from,
                                                  @Param("to") Instant to,
                                                  @Param("datasourceId") UUID datasourceId);

    @Query(value = """
            SELECT qr.submitted_by    AS user_id,
                   u.email            AS email,
                   u.display_name     AS display_name,
                   COUNT(*)           AS cnt
            FROM ai_analyses a
            JOIN query_requests qr ON qr.id = a.query_request_id
            JOIN datasources d     ON d.id = qr.datasource_id
            JOIN users u           ON u.id = qr.submitted_by
            WHERE d.organization_id = :organizationId
              AND a.created_at >= :from
              AND a.created_at <  :to
              AND (CAST(:datasourceId AS uuid) IS NULL
                   OR qr.datasource_id = CAST(:datasourceId AS uuid))
            GROUP BY qr.submitted_by, u.email, u.display_name
            ORDER BY cnt DESC
            LIMIT 10
            """, nativeQuery = true)
    List<SubmitterRow> findTopSubmitters(@Param("organizationId") UUID organizationId,
                                         @Param("from") Instant from,
                                         @Param("to") Instant to,
                                         @Param("datasourceId") UUID datasourceId);

    interface RiskScoreBucketRow {
        LocalDate getBucketDate();
        BigDecimal getSuccessAvgRiskScore();
        long getTotalCount();
        long getSuccessCount();
    }

    interface IssueCategoryRow {
        String getCategory();
        long getCnt();
    }

    interface SubmitterRow {
        UUID getUserId();
        String getEmail();
        String getDisplayName();
        long getCnt();
    }
}
