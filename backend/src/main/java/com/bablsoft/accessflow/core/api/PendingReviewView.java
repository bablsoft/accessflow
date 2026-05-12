package com.bablsoft.accessflow.core.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Aggregated view of a query request awaiting review: the query plus joined datasource and
 * submitter summary plus (optional) AI analysis summary. Used by the workflow module to render
 * the {@code GET /api/v1/reviews/pending} response without traversing JPA relationships across
 * module boundaries.
 */
public record PendingReviewView(
        UUID queryRequestId,
        UUID datasourceId,
        String datasourceName,
        UUID organizationId,
        UUID submittedByUserId,
        String submittedByEmail,
        String sqlText,
        QueryType queryType,
        QueryStatus status,
        String justification,
        UUID aiAnalysisId,
        RiskLevel aiRiskLevel,
        Integer aiRiskScore,
        String aiSummary,
        Instant createdAt) {
}
