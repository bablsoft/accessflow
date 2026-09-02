package com.bablsoft.accessflow.deploygov.api;

import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.SubmissionReason;

import java.time.Instant;
import java.util.UUID;

/**
 * One entry of an environment's deployment timeline (#742), derived straight from
 * {@code deployment_requests} — deliberately slimmer than {@link DeploymentRequestView}: the full
 * detail (AI analysis, decisions) stays one lookup away on the request endpoint.
 * {@code executedAt} is null for requests that never reached {@code EXECUTED}.
 */
public record DeploymentVersionHistoryEntryView(
        UUID requestId,
        String version,
        QueryStatus status,
        DeploymentOutcome outcome,
        Instant outcomeReportedAt,
        UUID submittedBy,
        SubmissionReason submissionReason,
        String commitSha,
        String runUrl,
        Instant createdAt,
        Instant executedAt) {
}
