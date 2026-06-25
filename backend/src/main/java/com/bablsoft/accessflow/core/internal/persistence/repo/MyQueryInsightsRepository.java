package com.bablsoft.accessflow.core.internal.persistence.repo;

import com.bablsoft.accessflow.core.internal.persistence.entity.QueryRequestEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

// Self-scoped (submitted_by) aggregations for the personalized dashboard (AF-498). Postgres-specific
// (date_trunc, jsonb_array_length); AccessFlow is Postgres-only — see CLAUDE.md. The org scope is
// applied through datasources.organization_id, mirroring AiAnalysisStatsRepository.
public interface MyQueryInsightsRepository extends JpaRepository<QueryRequestEntity, UUID> {

    @Query(value = """
            SELECT date_trunc('day', qr.created_at)::date AS bucket_date,
                   qr.status                              AS status,
                   COUNT(*)                               AS cnt
            FROM query_requests qr
            JOIN datasources d ON d.id = qr.datasource_id
            WHERE d.organization_id = :organizationId
              AND qr.submitted_by = :userId
              AND qr.created_at >= :from
              AND qr.created_at <  :to
            GROUP BY bucket_date, qr.status
            ORDER BY bucket_date ASC
            """, nativeQuery = true)
    List<StatusBucketRow> findStatusByDay(@Param("organizationId") UUID organizationId,
                                          @Param("userId") UUID userId,
                                          @Param("from") Instant from,
                                          @Param("to") Instant to);

    @Query(value = """
            SELECT date_trunc('day', qr.created_at)::date AS bucket_date,
                   a.risk_level                           AS risk_level,
                   COUNT(*)                               AS cnt
            FROM query_requests qr
            JOIN datasources d  ON d.id = qr.datasource_id
            JOIN ai_analyses a  ON a.id = qr.ai_analysis_id
            WHERE d.organization_id = :organizationId
              AND qr.submitted_by = :userId
              AND a.failed = false
              AND qr.created_at >= :from
              AND qr.created_at <  :to
            GROUP BY bucket_date, a.risk_level
            ORDER BY bucket_date ASC
            """, nativeQuery = true)
    List<RiskBucketRow> findRiskByDay(@Param("organizationId") UUID organizationId,
                                      @Param("userId") UUID userId,
                                      @Param("from") Instant from,
                                      @Param("to") Instant to);

    @Query(value = """
            SELECT qr.status AS status,
                   COUNT(*)  AS cnt
            FROM query_requests qr
            JOIN datasources d ON d.id = qr.datasource_id
            WHERE d.organization_id = :organizationId
              AND qr.submitted_by = :userId
            GROUP BY qr.status
            """, nativeQuery = true)
    List<StatusCountRow> findStatusCounts(@Param("organizationId") UUID organizationId,
                                          @Param("userId") UUID userId);

    @Query(value = """
            SELECT a.id               AS ai_analysis_id,
                   qr.id              AS query_request_id,
                   qr.datasource_id   AS datasource_id,
                   d.name             AS datasource_name,
                   d.db_type          AS db_type,
                   a.risk_level       AS risk_level,
                   a.optimizations::text AS optimizations,
                   a.created_at       AS created_at
            FROM ai_analyses a
            JOIN query_requests qr ON qr.id = a.query_request_id
            JOIN datasources d     ON d.id = qr.datasource_id
            WHERE d.organization_id = :organizationId
              AND qr.submitted_by = :userId
              AND a.failed = false
              AND jsonb_array_length(a.optimizations) > 0
            ORDER BY a.created_at DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<OptimizationSourceRow> findRecentOptimizationSources(@Param("organizationId") UUID organizationId,
                                                              @Param("userId") UUID userId,
                                                              @Param("limit") int limit);

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

    interface OptimizationSourceRow {
        UUID getAiAnalysisId();
        UUID getQueryRequestId();
        UUID getDatasourceId();
        String getDatasourceName();
        String getDbType();
        String getRiskLevel();
        String getOptimizations();
        Instant getCreatedAt();
    }
}
