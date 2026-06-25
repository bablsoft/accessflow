package com.bablsoft.accessflow.dashboard.internal.web;

import com.bablsoft.accessflow.core.api.QueryListItemView;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.RiskLevel;
import com.bablsoft.accessflow.dashboard.api.DashboardSummary;
import com.bablsoft.accessflow.workflow.api.ReviewService.PendingReview;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * API envelope for the dashboard summary (AF-498). Jackson converts camelCase → snake_case globally.
 */
public record DashboardSummaryResponse(
        long pendingApprovalsCount,
        long openQueriesCount,
        long openAnomaliesCount,
        long openSuggestionsCount,
        List<StatusCountResponse> statusCounts,
        List<RecentQueryResponse> recentQueries,
        List<PendingApprovalResponse> recentPendingApprovals) {

    public static DashboardSummaryResponse from(DashboardSummary s) {
        return new DashboardSummaryResponse(
                s.pendingApprovalsCount(),
                s.openQueriesCount(),
                s.openAnomaliesCount(),
                s.openSuggestionsCount(),
                s.statusCounts().stream()
                        .map(c -> new StatusCountResponse(c.status(), c.count())).toList(),
                s.recentQueries().stream().map(RecentQueryResponse::from).toList(),
                s.recentPendingApprovals().stream().map(PendingApprovalResponse::from).toList());
    }

    public record StatusCountResponse(QueryStatus status, long count) {
    }

    public record RecentQueryResponse(
            UUID id,
            UUID datasourceId,
            String datasourceName,
            QueryType queryType,
            QueryStatus status,
            RiskLevel aiRiskLevel,
            Integer aiRiskScore,
            boolean aiFailed,
            Instant createdAt) {

        static RecentQueryResponse from(QueryListItemView v) {
            return new RecentQueryResponse(v.id(), v.datasourceId(), v.datasourceName(),
                    v.queryType(), v.status(), v.aiRiskLevel(), v.aiRiskScore(), v.aiFailed(),
                    v.createdAt());
        }
    }

    public record PendingApprovalResponse(
            UUID queryRequestId,
            UUID datasourceId,
            String datasourceName,
            String submittedByEmail,
            QueryType queryType,
            RiskLevel aiRiskLevel,
            Integer aiRiskScore,
            int currentStage,
            Instant createdAt) {

        static PendingApprovalResponse from(PendingReview p) {
            return new PendingApprovalResponse(p.queryRequestId(), p.datasourceId(),
                    p.datasourceName(), p.submittedByEmail(), p.queryType(), p.aiRiskLevel(),
                    p.aiRiskScore(), p.currentStage(), p.createdAt());
        }
    }
}
