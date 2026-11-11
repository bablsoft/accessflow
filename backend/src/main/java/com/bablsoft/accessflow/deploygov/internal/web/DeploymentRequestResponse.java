package com.bablsoft.accessflow.deploygov.internal.web;

import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.RiskLevel;
import com.bablsoft.accessflow.core.api.SubmissionReason;
import com.bablsoft.accessflow.deploygov.api.DeploymentOutcome;
import com.bablsoft.accessflow.deploygov.api.DeploymentRequestView;
import com.bablsoft.accessflow.deploygov.api.DeploymentVerificationSnapshotView;
import com.bablsoft.accessflow.deploygov.api.PipelineProvider;
import com.bablsoft.accessflow.deploygov.api.VerificationStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record DeploymentRequestResponse(
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
        List<DeploymentReviewDecisionResponse> decisions,
        boolean canReview,
        /** The human an API-key submitter acted for (#874); null for a human submission. */
        UUID onBehalfOfUserId,
        String onBehalfOfEmail,
        /** Mirrors the environment's flag (#994). */
        boolean changeTicketRequired,
        /** The linked change ticket that explains the request's state, or null (#994). */
        DeploymentChangeTicketResponse changeTicket,
        /** Post-deployment verification state (#1025). */
        VerificationStatus verificationStatus,
        Instant verificationStartedAt,
        Instant verificationFinishedAt,
        String verificationDetail,
        /** The config the window was opened with (#1027); null (omitted) when never verified. */
        VerificationSnapshot verificationSnapshot) {

    /** Window and interval as ISO-8601 strings; no check URLs or queries. */
    public record VerificationSnapshot(String window, String sampleInterval, int failureThreshold,
                                       int checkCount) {

        static VerificationSnapshot from(DeploymentVerificationSnapshotView view) {
            if (view == null) {
                return null;
            }
            return new VerificationSnapshot(iso(view.window()), iso(view.sampleInterval()),
                    view.failureThreshold(), view.checkCount());
        }

        private static String iso(Duration duration) {
            return duration == null ? null : duration.toString();
        }
    }

    /** Every path but the detail read: reviewer eligibility is a detail-read concern, never computed here. */
    static DeploymentRequestResponse from(DeploymentRequestView view) {
        return from(view, false);
    }

    static DeploymentRequestResponse from(DeploymentRequestView view, boolean canReview) {
        return new DeploymentRequestResponse(view.id(), view.pipelineId(), view.pipelineName(),
                view.provider(), view.environmentId(), view.environmentName(), view.submittedBy(),
                view.submittedByEmail(), view.version(), view.commitSha(), view.artifactRef(),
                view.runUrl(), view.externalRunId(), view.metadata(), view.status(),
                view.submissionReason(), view.justification(), view.aiAnalysisId(),
                view.aiRiskLevel(), view.aiRiskScore(), view.aiSummary(), view.requiredApprovals(),
                view.scheduledFor(), view.outcome(), view.outcomeReportedAt(), view.outcomeDetail(),
                view.createdAt(),
                view.decisions().stream().map(DeploymentReviewDecisionResponse::from).toList(),
                canReview, view.onBehalfOfUserId(), view.onBehalfOfEmail(),
                view.changeTicketRequired(),
                DeploymentChangeTicketResponse.from(view.changeTicket()),
                view.verificationStatus(), view.verificationStartedAt(),
                view.verificationFinishedAt(), view.verificationDetail(),
                VerificationSnapshot.from(view.verificationSnapshot()));
    }
}
