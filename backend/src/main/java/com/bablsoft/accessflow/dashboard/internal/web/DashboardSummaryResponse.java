package com.bablsoft.accessflow.dashboard.internal.web;

import com.bablsoft.accessflow.apigov.api.ApiRequestView;
import com.bablsoft.accessflow.apigov.api.ApiReviewService.PendingApiReview;
import com.bablsoft.accessflow.core.api.QueryListItemView;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.RiskLevel;
import com.bablsoft.accessflow.dashboard.api.DashboardSummary;
import com.bablsoft.accessflow.deploygov.api.DeploymentOutcome;
import com.bablsoft.accessflow.deploygov.api.DeploymentRequestView;
import com.bablsoft.accessflow.deploygov.api.DeploymentReviewService.PendingDeploymentReview;
import com.bablsoft.accessflow.workflow.api.ReviewService.PendingReview;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * API envelope for the dashboard summary (AF-498). Jackson converts camelCase → snake_case globally.
 * The {@code apiRequest}/{@code pendingApiApproval} blocks back the API Access Governance widgets (AF-500),
 * and the {@code deployment}/{@code pendingDeploymentApproval} blocks the deployment-governance ones (#926).
 */
public record DashboardSummaryResponse(
        long pendingApprovalsCount,
        long openQueriesCount,
        long openAnomaliesCount,
        long openSuggestionsCount,
        long openApiRequestsCount,
        long pendingApiApprovalsCount,
        long openDeploymentsCount,
        long pendingDeploymentApprovalsCount,
        List<StatusCountResponse> statusCounts,
        List<RecentQueryResponse> recentQueries,
        List<PendingApprovalResponse> recentPendingApprovals,
        List<RecentApiRequestResponse> recentApiRequests,
        List<PendingApiApprovalResponse> recentPendingApiApprovals,
        List<RecentDeploymentResponse> recentDeployments,
        List<PendingDeploymentApprovalResponse> recentPendingDeploymentApprovals) {

    public static DashboardSummaryResponse from(DashboardSummary s) {
        return new DashboardSummaryResponse(
                s.pendingApprovalsCount(),
                s.openQueriesCount(),
                s.openAnomaliesCount(),
                s.openSuggestionsCount(),
                s.openApiRequestsCount(),
                s.pendingApiApprovalsCount(),
                s.openDeploymentsCount(),
                s.pendingDeploymentApprovalsCount(),
                s.statusCounts().stream()
                        .map(c -> new StatusCountResponse(c.status(), c.count())).toList(),
                s.recentQueries().stream().map(RecentQueryResponse::from).toList(),
                s.recentPendingApprovals().stream().map(PendingApprovalResponse::from).toList(),
                s.recentApiRequests().stream().map(RecentApiRequestResponse::from).toList(),
                s.recentPendingApiApprovals().stream().map(PendingApiApprovalResponse::from).toList(),
                s.recentDeployments().stream().map(RecentDeploymentResponse::from).toList(),
                s.recentPendingDeploymentApprovals().stream()
                        .map(PendingDeploymentApprovalResponse::from).toList());
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

    public record RecentDeploymentResponse(
            UUID id,
            UUID pipelineId,
            String pipelineName,
            UUID environmentId,
            String environmentName,
            String version,
            QueryStatus status,
            RiskLevel aiRiskLevel,
            Integer aiRiskScore,
            DeploymentOutcome outcome,
            Instant createdAt) {

        static RecentDeploymentResponse from(DeploymentRequestView v) {
            return new RecentDeploymentResponse(v.id(), v.pipelineId(), v.pipelineName(),
                    v.environmentId(), v.environmentName(), v.version(), v.status(),
                    v.aiRiskLevel(), v.aiRiskScore(), v.outcome(), v.createdAt());
        }
    }

    public record PendingDeploymentApprovalResponse(
            UUID deploymentRequestId,
            UUID pipelineId,
            String pipelineName,
            UUID environmentId,
            String environmentName,
            UUID submittedByUserId,
            String version,
            RiskLevel aiRiskLevel,
            Integer aiRiskScore,
            int currentStage,
            Instant createdAt) {

        static PendingDeploymentApprovalResponse from(PendingDeploymentReview p) {
            return new PendingDeploymentApprovalResponse(p.deploymentRequestId(), p.pipelineId(),
                    p.pipelineName(), p.environmentId(), p.environmentName(), p.submittedByUserId(),
                    p.version(), p.aiRiskLevel(), p.aiRiskScore(), p.currentStage(), p.createdAt());
        }
    }

    public record RecentApiRequestResponse(
            UUID id,
            UUID connectorId,
            String connectorName,
            String verb,
            String requestPath,
            boolean write,
            QueryStatus status,
            RiskLevel aiRiskLevel,
            Integer aiRiskScore,
            Instant createdAt) {

        static RecentApiRequestResponse from(ApiRequestView v) {
            return new RecentApiRequestResponse(v.id(), v.connectorId(), v.connectorName(), v.verb(),
                    v.requestPath(), v.write(), v.status(), v.aiRiskLevel(), v.aiRiskScore(),
                    v.createdAt());
        }
    }

    public record PendingApiApprovalResponse(
            UUID apiRequestId,
            UUID connectorId,
            String connectorName,
            UUID submittedByUserId,
            String verb,
            String requestPath,
            boolean write,
            RiskLevel aiRiskLevel,
            Integer aiRiskScore,
            int currentStage,
            Instant createdAt) {

        static PendingApiApprovalResponse from(PendingApiReview p) {
            return new PendingApiApprovalResponse(p.apiRequestId(), p.connectorId(),
                    p.connectorName(), p.submittedByUserId(), p.verb(), p.requestPath(), p.write(),
                    p.aiRiskLevel(), p.aiRiskScore(), p.currentStage(), p.createdAt());
        }
    }
}
