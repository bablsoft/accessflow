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
        List<DeploymentReviewDecisionView> decisions) {

    public DeploymentRequestView {
        // Tolerates null values: the metadata round-trips CI-authored JSON, where a null is legal.
        metadata = metadata == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
        decisions = decisions == null ? List.of() : List.copyOf(decisions);
    }
}
