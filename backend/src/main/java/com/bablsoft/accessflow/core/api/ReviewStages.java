package com.bablsoft.accessflow.core.api;

import java.util.List;

/**
 * The definition of a multi-stage review plan's <em>current</em> stage: the lowest stage that has
 * not yet collected {@code minApprovalsRequired} approvals.
 *
 * <p>Shared rather than duplicated because it decides two different things that must agree — who
 * may act on a request ({@code workflow}) and who gets told about it ({@code notifications}). A
 * second copy that drifted would notify one set of reviewers while authorizing another, and the
 * symptom (reminders going to people who already decided, while the people who have to act hear
 * nothing) looks like a mail problem rather than a logic one.
 */
public final class ReviewStages {

    private ReviewStages() {
    }

    /**
     * The stage a request is waiting on. Falls back to the plan's maximum stage when every stage
     * has met its threshold — the caller's status guard is what surfaces that as an error.
     */
    public static int current(ReviewPlanSnapshot plan, List<ReviewDecisionSnapshot> decisions,
                              int minApprovalsRequired) {
        var stages = plan.approvers().stream()
                .map(ApproverRule::stage)
                .distinct()
                .sorted()
                .toList();
        for (int stage : stages) {
            long approvedAtStage = decisions.stream()
                    .filter(d -> d.stage() == stage && d.decision() == DecisionType.APPROVED)
                    .count();
            if (approvedAtStage < minApprovalsRequired) {
                return stage;
            }
        }
        return plan.maxStage();
    }
}
