package com.bablsoft.accessflow.workflow.internal.hook;

import com.bablsoft.accessflow.workflow.api.DecisionHookFailure;
import com.bablsoft.accessflow.workflow.api.DecisionHookOutcome;
import com.bablsoft.accessflow.workflow.api.DecisionHookTestResult;

/**
 * What one decision hook call concluded (#945). A failure always carries {@link
 * DecisionHookOutcome#FAILED} and a {@link DecisionHookFailure}; nothing else ever does.
 *
 * @param requestedApprovals the approval count the hook asked for, validated to 1–10, or
 *                           {@code null} for {@code ALLOW} / {@code REJECT} / failures
 * @param httpStatus         the response status, {@code null} when no response arrived
 */
record DecisionHookVerdict(DecisionHookOutcome outcome, DecisionHookFailure failure,
                           Integer requestedApprovals, String reason, Integer httpStatus,
                           long latencyMs) {

    static DecisionHookVerdict failed(DecisionHookFailure failure, Integer httpStatus,
                                      long latencyMs) {
        return new DecisionHookVerdict(DecisionHookOutcome.FAILED, failure, null, null, httpStatus,
                latencyMs);
    }

    boolean isFailure() {
        return outcome == DecisionHookOutcome.FAILED;
    }

    DecisionHookTestResult toTestResult() {
        return new DecisionHookTestResult(outcome, failure, requestedApprovals, reason, httpStatus,
                latencyMs);
    }
}
