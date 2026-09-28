package com.bablsoft.accessflow.workflow.api;

/**
 * What one synthetic call to a decision hook concluded (#945).
 *
 * @param failure            set only when {@code outcome} is {@link DecisionHookOutcome#FAILED}
 * @param requestedApprovals the approval count the hook asked for, when it asked for one
 * @param httpStatus         the response status, {@code null} when no response arrived
 */
public record DecisionHookTestResult(
        DecisionHookOutcome outcome,
        DecisionHookFailure failure,
        Integer requestedApprovals,
        String reason,
        Integer httpStatus,
        long latencyMs) {
}
