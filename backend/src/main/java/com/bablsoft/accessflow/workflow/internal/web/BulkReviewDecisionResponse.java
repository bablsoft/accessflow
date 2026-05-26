package com.bablsoft.accessflow.workflow.internal.web;

import com.bablsoft.accessflow.workflow.api.ReviewService.BulkDecisionOutcome;

import java.util.List;

public record BulkReviewDecisionResponse(List<BulkReviewRowResult> results) {

    public static BulkReviewDecisionResponse from(BulkDecisionOutcome outcome) {
        var results = outcome.rows().stream()
                .map(BulkReviewRowResult::from)
                .toList();
        return new BulkReviewDecisionResponse(results);
    }
}
