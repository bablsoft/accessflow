package com.bablsoft.accessflow.workflow.internal.web;

import com.bablsoft.accessflow.core.api.DecisionType;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

public record BulkReviewDecisionRequest(
        @NotEmpty(message = "{validation.bulk_review.query_ids.required}")
        @Size(max = 100, message = "{validation.bulk_review.query_ids.max}")
        List<UUID> queryIds,

        @NotNull(message = "{validation.bulk_review.decision.required}")
        DecisionType decision,

        @Size(max = 4000, message = "{validation.review_comment.max}")
        String comment) {

    public boolean requiresComment() {
        return decision == DecisionType.REJECTED || decision == DecisionType.REQUESTED_CHANGES;
    }
}
