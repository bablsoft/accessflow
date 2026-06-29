package com.bablsoft.accessflow.apigov.internal.persistence.repo;

import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiRequestEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

// Self-scoped (submitted_by) aggregations for the API-request dashboard widgets (AF-498). Postgres-specific
// (date_trunc); AccessFlow is Postgres-only — see CLAUDE.md. Unlike the SQL-query analogue
// (MyQueryInsightsRepository) the org scope is a direct column on api_requests, so there is no datasource
// join; only the risk-trend query joins ai_analyses (keyed by api_requests.ai_analysis_id).
public interface MyApiRequestInsightsRepository extends JpaRepository<ApiRequestEntity, UUID> {

    @Query(value = """
            SELECT date_trunc('day', ar.created_at)::date AS bucket_date,
                   ar.status                              AS status,
                   COUNT(*)                               AS cnt
            FROM api_requests ar
            WHERE ar.organization_id = :organizationId
              AND ar.submitted_by = :userId
              AND ar.created_at >= :from
              AND ar.created_at <  :to
            GROUP BY bucket_date, ar.status
            ORDER BY bucket_date ASC
            """, nativeQuery = true)
    List<StatusBucketRow> findStatusByDay(@Param("organizationId") UUID organizationId,
                                          @Param("userId") UUID userId,
                                          @Param("from") Instant from,
                                          @Param("to") Instant to);

    @Query(value = """
            SELECT date_trunc('day', ar.created_at)::date AS bucket_date,
                   a.risk_level                           AS risk_level,
                   COUNT(*)                               AS cnt
            FROM api_requests ar
            JOIN ai_analyses a ON a.id = ar.ai_analysis_id
            WHERE ar.organization_id = :organizationId
              AND ar.submitted_by = :userId
              AND a.failed = false
              AND ar.created_at >= :from
              AND ar.created_at <  :to
            GROUP BY bucket_date, a.risk_level
            ORDER BY bucket_date ASC
            """, nativeQuery = true)
    List<RiskBucketRow> findRiskByDay(@Param("organizationId") UUID organizationId,
                                      @Param("userId") UUID userId,
                                      @Param("from") Instant from,
                                      @Param("to") Instant to);

    @Query(value = """
            SELECT ar.status AS status,
                   COUNT(*)  AS cnt
            FROM api_requests ar
            WHERE ar.organization_id = :organizationId
              AND ar.submitted_by = :userId
            GROUP BY ar.status
            """, nativeQuery = true)
    List<StatusCountRow> findStatusCounts(@Param("organizationId") UUID organizationId,
                                          @Param("userId") UUID userId);

    interface StatusBucketRow {
        LocalDate getBucketDate();
        String getStatus();
        long getCnt();
    }

    interface RiskBucketRow {
        LocalDate getBucketDate();
        String getRiskLevel();
        long getCnt();
    }

    interface StatusCountRow {
        String getStatus();
        long getCnt();
    }
}
