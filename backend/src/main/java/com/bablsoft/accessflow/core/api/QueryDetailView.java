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
        ApprovalPredictionDetail approvalPrediction,
        Long rowsAffected,
        Integer durationMs,
        String errorMessage,
        UUID previousRunId,
        UUID approvedByGrantId,
        String reviewPlanName,
        Integer approvalTimeoutHours,
        /** When the escalation job raised this request (#622); null when never escalated. */
        Instant escalatedAt,
        /** The plan's escalation window, for the banner copy. Null when escalation is off. */
        Integer escalationAfterHours,
        List<ReviewDecisionView> reviewDecisions,
        Instant scheduledFor,
        String recurrenceRule,
        Instant recurrenceUntil,
        Instant recurrenceNextRunAt,
        String recurrenceHaltedReason,
        UUID recurringParentId,
        Instant createdAt,
        Instant updatedAt,
        /** The human an API-key submitter acted for (#874); null for a human submission. */
        UUID onBehalfOfUserId,
        String onBehalfOfEmail,
        /** The calling application (#938) and how it was learned; both null when unknown. */
        String applicationName,
        ApplicationNameSource applicationNameSource,
        /** The bytes-scanned cap (#941) that applied when the query left PENDING_AI; null when none. */
        BytesScannedCapDetail bytesScannedCap) {

    /** Backward-compatible constructor without the #941 bytes-scanned cap. */
    public QueryDetailView(UUID id, UUID datasourceId, String datasourceName, DbType dbType,
                           UUID organizationId, UUID submittedByUserId, String submittedByEmail,
                           String submittedByDisplayName, String sqlText, QueryType queryType,
                           QueryStatus status, String justification, AiAnalysisDetail aiAnalysis,
                           CostEstimateDetail costEstimate,
                           ApprovalPredictionDetail approvalPrediction, Long rowsAffected,
                           Integer durationMs, String errorMessage, UUID previousRunId,
                           UUID approvedByGrantId, String reviewPlanName,
                           Integer approvalTimeoutHours, Instant escalatedAt,
                           Integer escalationAfterHours, List<ReviewDecisionView> reviewDecisions,
                           Instant scheduledFor, String recurrenceRule, Instant recurrenceUntil,
                           Instant recurrenceNextRunAt, String recurrenceHaltedReason,
                           UUID recurringParentId, Instant createdAt, Instant updatedAt,
                           UUID onBehalfOfUserId, String onBehalfOfEmail, String applicationName,
                           ApplicationNameSource applicationNameSource) {
        this(id, datasourceId, datasourceName, dbType, organizationId, submittedByUserId,
                submittedByEmail, submittedByDisplayName, sqlText, queryType, status, justification,
                aiAnalysis, costEstimate, approvalPrediction, rowsAffected, durationMs,
                errorMessage, previousRunId, approvedByGrantId, reviewPlanName,
                approvalTimeoutHours, escalatedAt, escalationAfterHours, reviewDecisions,
                scheduledFor, recurrenceRule, recurrenceUntil, recurrenceNextRunAt,
                recurrenceHaltedReason, recurringParentId, createdAt, updatedAt, onBehalfOfUserId,
                onBehalfOfEmail, applicationName, applicationNameSource, null);
    }

    /** The bytes-scanned cap (#941) recorded on the request, and how its estimate compared. */
    public record BytesScannedCapDetail(long limit, BytesScannedCapSource source,
                                        BytesScannedCapOutcome outcome) {
    }

    /** Backward-compatible constructor without the #938 calling application. */
    public QueryDetailView(UUID id, UUID datasourceId, String datasourceName, DbType dbType,
                           UUID organizationId, UUID submittedByUserId, String submittedByEmail,
                           String submittedByDisplayName, String sqlText, QueryType queryType,
                           QueryStatus status, String justification, AiAnalysisDetail aiAnalysis,
                           CostEstimateDetail costEstimate,
                           ApprovalPredictionDetail approvalPrediction, Long rowsAffected,
                           Integer durationMs, String errorMessage, UUID previousRunId,
                           UUID approvedByGrantId, String reviewPlanName,
                           Integer approvalTimeoutHours, Instant escalatedAt,
                           Integer escalationAfterHours, List<ReviewDecisionView> reviewDecisions,
                           Instant scheduledFor, String recurrenceRule, Instant recurrenceUntil,
                           Instant recurrenceNextRunAt, String recurrenceHaltedReason,
                           UUID recurringParentId, Instant createdAt, Instant updatedAt,
                           UUID onBehalfOfUserId, String onBehalfOfEmail) {
        this(id, datasourceId, datasourceName, dbType, organizationId, submittedByUserId,
                submittedByEmail, submittedByDisplayName, sqlText, queryType, status, justification,
                aiAnalysis, costEstimate, approvalPrediction, rowsAffected, durationMs,
                errorMessage, previousRunId, approvedByGrantId, reviewPlanName,
                approvalTimeoutHours, escalatedAt, escalationAfterHours, reviewDecisions,
                scheduledFor, recurrenceRule, recurrenceUntil, recurrenceNextRunAt,
                recurrenceHaltedReason, recurringParentId, createdAt, updatedAt, onBehalfOfUserId,
                onBehalfOfEmail, (String) null, (ApplicationNameSource) null);
    }

    /** Backward-compatible constructor without the #874 on-behalf-of principal. */
    public QueryDetailView(UUID id, UUID datasourceId, String datasourceName, DbType dbType,
                           UUID organizationId, UUID submittedByUserId, String submittedByEmail,
                           String submittedByDisplayName, String sqlText, QueryType queryType,
                           QueryStatus status, String justification, AiAnalysisDetail aiAnalysis,
                           CostEstimateDetail costEstimate,
                           ApprovalPredictionDetail approvalPrediction, Long rowsAffected,
                           Integer durationMs, String errorMessage, UUID previousRunId,
                           UUID approvedByGrantId, String reviewPlanName,
                           Integer approvalTimeoutHours, Instant escalatedAt,
                           Integer escalationAfterHours, List<ReviewDecisionView> reviewDecisions,
                           Instant scheduledFor, String recurrenceRule, Instant recurrenceUntil,
                           Instant recurrenceNextRunAt, String recurrenceHaltedReason,
                           UUID recurringParentId, Instant createdAt, Instant updatedAt) {
        this(id, datasourceId, datasourceName, dbType, organizationId, submittedByUserId,
                submittedByEmail, submittedByDisplayName, sqlText, queryType, status, justification,
                aiAnalysis, costEstimate, approvalPrediction, rowsAffected, durationMs,
                errorMessage, previousRunId, approvedByGrantId, reviewPlanName,
                approvalTimeoutHours, escalatedAt, escalationAfterHours, reviewDecisions,
                scheduledFor, recurrenceRule, recurrenceUntil, recurrenceNextRunAt,
                recurrenceHaltedReason, recurringParentId, createdAt, updatedAt, null, null);
    }

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

    /** Backward-compatible constructor without the #627 recurrence fields (defaults to absent). */
    public QueryDetailView(UUID id, UUID datasourceId, String datasourceName, DbType dbType,
                           UUID organizationId, UUID submittedByUserId, String submittedByEmail,
                           String submittedByDisplayName, String sqlText, QueryType queryType,
                           QueryStatus status, String justification, AiAnalysisDetail aiAnalysis,
                           CostEstimateDetail costEstimate,
                           ApprovalPredictionDetail approvalPrediction, Long rowsAffected,
                           Integer durationMs, String errorMessage, UUID previousRunId,
                           UUID approvedByGrantId, String reviewPlanName,
                           Integer approvalTimeoutHours, List<ReviewDecisionView> reviewDecisions,
                           Instant scheduledFor, Instant createdAt, Instant updatedAt) {
        this(id, datasourceId, datasourceName, dbType, organizationId, submittedByUserId,
                submittedByEmail, submittedByDisplayName, sqlText, queryType, status, justification,
                aiAnalysis, costEstimate, approvalPrediction, rowsAffected, durationMs,
                errorMessage, previousRunId, approvedByGrantId, reviewPlanName,
                approvalTimeoutHours, null, null, reviewDecisions, scheduledFor, null, null, null,
                null, null, createdAt, updatedAt);
    }

    /**
     * Backward-compatible constructor without the AF-645 approval prediction (defaults to absent).
     * Chains from the cost-estimate overload above, so the three arities form 24 → 25 → canonical.
     */
    public QueryDetailView(UUID id, UUID datasourceId, String datasourceName, DbType dbType,
                           UUID organizationId, UUID submittedByUserId, String submittedByEmail,
                           String submittedByDisplayName, String sqlText, QueryType queryType,
                           QueryStatus status, String justification, AiAnalysisDetail aiAnalysis,
                           CostEstimateDetail costEstimate, Long rowsAffected, Integer durationMs,
                           String errorMessage, UUID previousRunId, UUID approvedByGrantId,
                           String reviewPlanName, Integer approvalTimeoutHours,
                           List<ReviewDecisionView> reviewDecisions, Instant scheduledFor,
                           Instant createdAt, Instant updatedAt) {
        this(id, datasourceId, datasourceName, dbType, organizationId, submittedByUserId,
                submittedByEmail, submittedByDisplayName, sqlText, queryType, status, justification,
                aiAnalysis, costEstimate, null, rowsAffected, durationMs, errorMessage,
                previousRunId, approvedByGrantId, reviewPlanName, approvalTimeoutHours,
                reviewDecisions, scheduledFor, createdAt, updatedAt);
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
            Integer durationMs,
            /** The warehouse engines' pre-flight scan estimate in raw bytes (#941); else null. */
            Long estimatedBytesScanned) {

        /** Backward-compatible constructor without the #941 bytes estimate. */
        public CostEstimateDetail(UUID id, String engineId, QueryType queryType, boolean supported,
                                  Long estimatedRows, Long affectedRowCount, String scanType,
                                  Double estimatedCost, String planJson, String rawPlan,
                                  String unsupportedReason, boolean failed, String errorMessage,
                                  Integer durationMs) {
            this(id, engineId, queryType, supported, estimatedRows, affectedRowCount, scanType,
                    estimatedCost, planJson, rawPlan, unsupportedReason, failed, errorMessage,
                    durationMs, null);
        }
    }

    /**
     * The advisory approval-outcome prediction (AF-645), when a row has been persisted.
     * {@code probability} is {@code null} on the {@code skipped} and {@code failed} sentinel rows;
     * {@code skippedReason} is a machine token ({@code DISABLED} / {@code MODEL_NOT_SERVING})
     * localized by the reader, never at write time.
     */
    public record ApprovalPredictionDetail(
            UUID id,
            Double probability,
            boolean skipped,
            String skippedReason,
            boolean failed,
            Instant createdAt) {
    }

    public record ReviewDecisionView(
            UUID id,
            ReviewerRef reviewer,
            DecisionType decision,
            String comment,
            int stage,
            Instant decidedAt,
            /** The delegator whose authority was borrowed (#622); null for an own-authority vote. */
            ReviewerRef onBehalfOf) {

        /** Convenience constructor for a decision taken under the reviewer's own authority. */
        public ReviewDecisionView(UUID id, ReviewerRef reviewer, DecisionType decision,
                                  String comment, int stage, Instant decidedAt) {
            this(id, reviewer, decision, comment, stage, decidedAt, null);
        }
    }

    public record ReviewerRef(
            UUID id,
            String email,
            String displayName) {
    }
}
