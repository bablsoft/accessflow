package com.bablsoft.accessflow.deploygov.internal.web;

import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.SubmissionReason;
import com.bablsoft.accessflow.deploygov.api.DeploymentOutcome;
import com.bablsoft.accessflow.deploygov.api.DeploymentVersionHistoryEntryView;

import java.time.Instant;
import java.util.UUID;

public record DeploymentVersionHistoryEntryResponse(
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

    static DeploymentVersionHistoryEntryResponse from(DeploymentVersionHistoryEntryView view) {
        return new DeploymentVersionHistoryEntryResponse(view.requestId(), view.version(),
                view.status(), view.outcome(), view.outcomeReportedAt(), view.submittedBy(),
                view.submissionReason(), view.commitSha(), view.runUrl(), view.createdAt(),
                view.executedAt());
    }
}
