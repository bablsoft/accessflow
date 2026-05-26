package com.bablsoft.accessflow.workflow.internal.web;

import com.bablsoft.accessflow.core.api.DecisionType;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.workflow.api.ReviewService.RowOutcome;
import com.bablsoft.accessflow.workflow.api.ReviewService.RowStatus;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

import java.util.UUID;

@JsonInclude(Include.NON_NULL)
public record BulkReviewRowResult(
        UUID queryRequestId,
        RowStatus status,
        DecisionType decision,
        QueryStatus resultingStatus,
        UUID decisionId,
        Boolean idempotentReplay,
        String error,
        String errorCode) {

    public static BulkReviewRowResult from(RowOutcome row) {
        if (row.status() == RowStatus.SUCCESS) {
            var outcome = row.outcome();
            return new BulkReviewRowResult(
                    row.queryRequestId(),
                    RowStatus.SUCCESS,
                    outcome.decision(),
                    outcome.resultingStatus(),
                    outcome.decisionId(),
                    outcome.wasIdempotentReplay(),
                    null,
                    null);
        }
        return new BulkReviewRowResult(
                row.queryRequestId(),
                row.status(),
                null,
                null,
                null,
                null,
                row.errorMessage(),
                row.errorCode());
    }
}
