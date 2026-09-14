package com.bablsoft.accessflow.workflow.internal;

import java.util.List;

/**
 * What a {@code BLOCK} SQL review finding actually changed about a decision (#864): the auto-approve
 * paths it turned into human review. Only built when at least one path was suppressed — a block on a
 * request the plan would have sent to review anyway is recorded on the trace but is not a
 * suppression, and writes no {@code SQL_REVIEW_BLOCKED} audit row.
 *
 * @param blockingRuleIds the distinct rule ids that fired at {@code BLOCK}, sorted
 * @param paths           the suppressed paths in evaluation order; never empty
 */
record SqlReviewSuppression(List<String> blockingRuleIds, List<SuppressedAutoApproval> paths) {

    SqlReviewSuppression {
        blockingRuleIds = List.copyOf(blockingRuleIds);
        paths = List.copyOf(paths);
        if (paths.isEmpty()) {
            throw new IllegalArgumentException("a suppression names at least one path");
        }
    }

    /** The three ways a query leaves {@code PENDING_AI} as {@code APPROVED} without a person. */
    enum SuppressedAutoApproval {
        ROUTING_AUTO_APPROVE,
        GRANT_FAST_PATH,
        REVIEW_PLAN
    }
}
