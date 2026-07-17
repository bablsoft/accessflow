package com.bablsoft.accessflow.workflow.internal.web;

import com.fasterxml.jackson.annotation.JsonRawValue;
import com.bablsoft.accessflow.access.api.AccessGrantView;
import com.bablsoft.accessflow.core.api.AiProviderType;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.DecisionType;
import com.bablsoft.accessflow.core.api.QueryDetailView;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.QueryTicketView;
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
        ApprovedByGrantDetail approvedByGrant,
        List<ReviewDecisionDetail> reviewDecisions,
        List<LinkedTicketDetail> linkedTickets,
        Instant scheduledFor,
        Instant createdAt,
        Instant updatedAt) {

    public static QueryDetailResponse from(QueryDetailView view) {
        return from(view, null, null);
    }

    public static QueryDetailResponse from(QueryDetailView view, MatchedRoutingPolicyView matched) {
        return from(view, matched, null);
    }

    public static QueryDetailResponse from(QueryDetailView view, MatchedRoutingPolicyView matched,
                                           AccessGrantView grant) {
        return from(view, matched, grant, List.of());
    }

    public static QueryDetailResponse from(QueryDetailView view, MatchedRoutingPolicyView matched,
                                           AccessGrantView grant, List<QueryTicketView> tickets) {
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
                ApprovedByGrantDetail.from(view.approvedByGrantId(), grant),
                view.reviewDecisions().stream().map(ReviewDecisionDetail::from).toList(),
                tickets == null
                        ? List.of()
                        : tickets.stream().map(LinkedTicketDetail::from).toList(),
                view.scheduledFor(),
                view.createdAt(),
                view.updatedAt());
    }

    /** A ticket auto-created in an external ticketing system for this query (AF-453). */
    public record LinkedTicketDetail(
            UUID id,
            String system,
            String triggerEvent,
            String externalKey,
            String url,
            String status,
            String resolution,
            Instant createdAt,
            Instant updatedAt) {

        static LinkedTicketDetail from(QueryTicketView src) {
            return new LinkedTicketDetail(
                    src.id(),
                    src.ticketSystem(),
                    src.triggerEvent(),
                    src.externalKey(),
                    src.url(),
                    src.status(),
                    src.resolution(),
                    src.createdAt(),
                    src.updatedAt());
        }
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

    /**
     * Provenance of a grant-covered auto-approval (#582). {@code grantId} always reflects the
     * query's {@code approved_by_grant_id}; the approver fields are null when the grant row (or
     * its approver) is no longer resolvable.
     */
    public record ApprovedByGrantDetail(
            UUID grantId,
            UUID approverId,
            String approverEmail,
            Instant approvedAt,
            Instant expiresAt) {

        static ApprovedByGrantDetail from(UUID approvedByGrantId, AccessGrantView grant) {
            if (approvedByGrantId == null) {
                return null;
            }
            if (grant == null) {
                return new ApprovedByGrantDetail(approvedByGrantId, null, null, null, null);
            }
            return new ApprovedByGrantDetail(grant.id(), grant.approverId(), grant.approverEmail(),
                    grant.approvedAt(), grant.expiresAt());
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
