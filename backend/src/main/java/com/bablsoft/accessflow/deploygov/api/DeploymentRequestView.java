package com.bablsoft.accessflow.deploygov.api;

import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.RiskLevel;
import com.bablsoft.accessflow.core.api.SubmissionReason;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Detail view of a governed deployment request. The {@code ai*} fields are null until the analysis
 * completes, and stay null when the pipeline has AI analysis disabled. {@code decisions} is empty
 * until the review flow (#692) records any.
 */
public record DeploymentRequestView(
        UUID id,
        UUID pipelineId,
        String pipelineName,
        PipelineProvider provider,
        UUID environmentId,
        String environmentName,
        UUID submittedBy,
        String submittedByEmail,
        String version,
        String commitSha,
        String artifactRef,
        String runUrl,
        String externalRunId,
        Map<String, Object> metadata,
        QueryStatus status,
        SubmissionReason submissionReason,
        String justification,
        UUID aiAnalysisId,
        RiskLevel aiRiskLevel,
        Integer aiRiskScore,
        String aiSummary,
        int requiredApprovals,
        Instant scheduledFor,
        DeploymentOutcome outcome,
        Instant outcomeReportedAt,
        String outcomeDetail,
        Instant createdAt,
        List<DeploymentReviewDecisionView> decisions,
        /** The human an API-key submitter acted for (#874); null for a human submission. */
        UUID onBehalfOfUserId,
        String onBehalfOfEmail) {

    /** Backward-compatible constructor without the #874 on-behalf-of principal. */
    public DeploymentRequestView(UUID id, UUID pipelineId, String pipelineName, PipelineProvider provider,
                                 UUID environmentId, String environmentName, UUID submittedBy,
                                 String submittedByEmail, String version, String commitSha,
                                 String artifactRef, String runUrl, String externalRunId,
                                 Map<String, Object> metadata, QueryStatus status,
                                 SubmissionReason submissionReason, String justification,
                                 UUID aiAnalysisId, RiskLevel aiRiskLevel, Integer aiRiskScore,
                                 String aiSummary, int requiredApprovals, Instant scheduledFor,
                                 DeploymentOutcome outcome, Instant outcomeReportedAt,
                                 String outcomeDetail, Instant createdAt,
                                 List<DeploymentReviewDecisionView> decisions) {
        this(id, pipelineId, pipelineName, provider, environmentId, environmentName, submittedBy,
                submittedByEmail, version, commitSha, artifactRef, runUrl, externalRunId, metadata,
                status, submissionReason, justification, aiAnalysisId, aiRiskLevel, aiRiskScore,
                aiSummary, requiredApprovals, scheduledFor, outcome, outcomeReportedAt, outcomeDetail,
                createdAt, decisions, null, null);
    }

    public DeploymentRequestView {
        // Tolerates null values: the metadata round-trips CI-authored JSON, where a null is legal.
        metadata = metadata == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
        decisions = decisions == null ? List.of() : List.copyOf(decisions);
    }
}
