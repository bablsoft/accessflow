package com.bablsoft.accessflow.core.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Cross-module DTO for {@code GET /queries/{id}}: the full read-side view of a query request,
 * including its datasource, submitter, AI analysis (when present), and execution outcome.
 */
public record QueryDetailView(
        UUID id,
        UUID datasourceId,
        String datasourceName,
        DbType dbType,
        UUID organizationId,
        UUID submittedByUserId,
        String submittedByEmail,
        String submittedByDisplayName,
        String sqlText,
        QueryType queryType,
        QueryStatus status,
        String justification,
        AiAnalysisDetail aiAnalysis,
        CostEstimateDetail costEstimate,
        Long rowsAffected,
        Integer durationMs,
        String errorMessage,
        UUID previousRunId,
        UUID approvedByGrantId,
        String reviewPlanName,
        Integer approvalTimeoutHours,
        List<ReviewDecisionView> reviewDecisions,
        Instant scheduledFor,
        Instant createdAt,
        Instant updatedAt) {

    /** Backward-compatible constructor without the AF-624 cost estimate (defaults to absent). */
    public QueryDetailView(UUID id, UUID datasourceId, String datasourceName, DbType dbType,
                           UUID organizationId, UUID submittedByUserId, String submittedByEmail,
                           String submittedByDisplayName, String sqlText, QueryType queryType,
                           QueryStatus status, String justification, AiAnalysisDetail aiAnalysis,
                           Long rowsAffected, Integer durationMs, String errorMessage,
                           UUID previousRunId, UUID approvedByGrantId, String reviewPlanName,
                           Integer approvalTimeoutHours, List<ReviewDecisionView> reviewDecisions,
                           Instant scheduledFor, Instant createdAt, Instant updatedAt) {
        this(id, datasourceId, datasourceName, dbType, organizationId, submittedByUserId,
                submittedByEmail, submittedByDisplayName, sqlText, queryType, status, justification,
                aiAnalysis, null, rowsAffected, durationMs, errorMessage, previousRunId,
                approvedByGrantId, reviewPlanName, approvalTimeoutHours, reviewDecisions,
                scheduledFor, createdAt, updatedAt);
    }

    public record AiAnalysisDetail(
            UUID id,
            RiskLevel riskLevel,
            int riskScore,
            String summary,
            String issuesJson,
            String optimizationsJson,
            boolean missingIndexesDetected,
            Long affectsRowEstimate,
            AiProviderType aiProvider,
            String aiModel,
            int promptTokens,
            int completionTokens,
            boolean failed,
            String errorMessage) {
    }

    /** The persisted pre-flight cost / blast-radius estimate (AF-624), when computed. */
    public record CostEstimateDetail(
            UUID id,
            String engineId,
            QueryType queryType,
            boolean supported,
            Long estimatedRows,
            Long affectedRowCount,
            String scanType,
            Double estimatedCost,
            String planJson,
            String rawPlan,
            String unsupportedReason,
            boolean failed,
            String errorMessage,
            Integer durationMs) {
    }

    public record ReviewDecisionView(
            UUID id,
            ReviewerRef reviewer,
            DecisionType decision,
            String comment,
            int stage,
            Instant decidedAt) {
    }

    public record ReviewerRef(
            UUID id,
            String email,
            String displayName) {
    }
}
