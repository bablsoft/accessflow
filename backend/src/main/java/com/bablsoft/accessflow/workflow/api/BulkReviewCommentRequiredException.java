package com.bablsoft.accessflow.workflow.api;

import com.bablsoft.accessflow.core.api.DecisionType;

/**
 * Thrown when a bulk-review request omits a comment that is mandatory for the requested
 * decision (REJECTED or REQUESTED_CHANGES). Mapped to HTTP 400 by the review controller-advice.
 */
public final class BulkReviewCommentRequiredException extends RuntimeException {

    private final DecisionType decision;

    public BulkReviewCommentRequiredException(DecisionType decision) {
        super("Comment is required for bulk decision " + decision);
        this.decision = decision;
    }

    public DecisionType decision() {
        return decision;
    }
}
