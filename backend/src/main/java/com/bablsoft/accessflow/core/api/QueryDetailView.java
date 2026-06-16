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
        Long rowsAffected,
        Integer durationMs,
        String errorMessage,
        UUID previousRunId,
        String reviewPlanName,
        Integer approvalTimeoutHours,
        List<ReviewDecisionView> reviewDecisions,
        Instant scheduledFor,
        Instant createdAt,
        Instant updatedAt) {

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
