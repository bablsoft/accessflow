package com.bablsoft.accessflow.workflow.internal.web;

import com.fasterxml.jackson.annotation.JsonRawValue;
import com.bablsoft.accessflow.core.api.AiProviderType;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.DecisionType;
import com.bablsoft.accessflow.core.api.QueryDetailView;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.RiskLevel;
import com.bablsoft.accessflow.workflow.api.RoutingAction;
import com.bablsoft.accessflow.workflow.internal.routing.MatchedRoutingPolicyView;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Response body for {@code GET /queries/{id}}. */
public record QueryDetailResponse(
        UUID id,
        QueryListItem.DatasourceRef datasource,
        DbType dbType,
        QueryListItem.SubmitterRef submittedBy,
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
        MatchedPolicyDetail matchedPolicy,
        List<ReviewDecisionDetail> reviewDecisions,
        Instant scheduledFor,
        Instant createdAt,
        Instant updatedAt) {

    public static QueryDetailResponse from(QueryDetailView view) {
        return from(view, null);
    }

    public static QueryDetailResponse from(QueryDetailView view, MatchedRoutingPolicyView matched) {
        return new QueryDetailResponse(
                view.id(),
                new QueryListItem.DatasourceRef(view.datasourceId(), view.datasourceName()),
                view.dbType(),
                new QueryListItem.SubmitterRef(view.submittedByUserId(),
                        view.submittedByEmail(), view.submittedByDisplayName()),
                view.sqlText(),
                view.queryType(),
                view.status(),
                view.justification(),
                AiAnalysisDetail.from(view.aiAnalysis()),
                view.rowsAffected(),
                view.durationMs(),
                view.errorMessage(),
                view.previousRunId(),
                view.reviewPlanName(),
                view.approvalTimeoutHours(),
                MatchedPolicyDetail.from(matched),
                view.reviewDecisions().stream().map(ReviewDecisionDetail::from).toList(),
                view.scheduledFor(),
                view.createdAt(),
                view.updatedAt());
    }

    public record MatchedPolicyDetail(
            UUID policyId,
            String policyName,
            RoutingAction action,
            String reason) {

        static MatchedPolicyDetail from(MatchedRoutingPolicyView src) {
            if (src == null) {
                return null;
            }
            return new MatchedPolicyDetail(src.policyId(), src.policyName(), src.action(),
                    src.reason());
        }
    }

    public record AiAnalysisDetail(
            UUID id,
            RiskLevel riskLevel,
            int riskScore,
            String summary,
            @JsonRawValue String issues,
            @JsonRawValue String optimizations,
            boolean missingIndexesDetected,
            Long affectsRowEstimate,
            AiProviderType aiProvider,
            String aiModel,
            int promptTokens,
            int completionTokens,
            boolean failed,
            String errorMessage) {

        static AiAnalysisDetail from(QueryDetailView.AiAnalysisDetail src) {
            if (src == null) {
                return null;
            }
            return new AiAnalysisDetail(
                    src.id(),
                    src.riskLevel(),
                    src.riskScore(),
                    src.summary(),
                    src.issuesJson() != null ? src.issuesJson() : "[]",
                    src.optimizationsJson() != null ? src.optimizationsJson() : "[]",
                    src.missingIndexesDetected(),
                    src.affectsRowEstimate(),
                    src.aiProvider(),
                    src.aiModel(),
                    src.promptTokens(),
                    src.completionTokens(),
                    src.failed(),
                    src.errorMessage());
        }
    }

    public record ReviewDecisionDetail(
            UUID id,
            ReviewerRef reviewer,
            DecisionType decision,
            String comment,
            int stage,
            Instant decidedAt) {

        static ReviewDecisionDetail from(QueryDetailView.ReviewDecisionView src) {
            return new ReviewDecisionDetail(
                    src.id(),
                    new ReviewerRef(
                            src.reviewer().id(),
                            src.reviewer().email(),
                            src.reviewer().displayName()),
                    src.decision(),
                    src.comment(),
                    src.stage(),
                    src.decidedAt());
        }
    }

    public record ReviewerRef(
            UUID id,
            String email,
            String displayName) {
    }
}
