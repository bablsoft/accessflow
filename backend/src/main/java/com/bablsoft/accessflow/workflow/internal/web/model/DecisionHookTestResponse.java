package com.bablsoft.accessflow.workflow.internal.web.model;

import com.bablsoft.accessflow.workflow.api.DecisionHookFailure;
import com.bablsoft.accessflow.workflow.api.DecisionHookOutcome;
import com.bablsoft.accessflow.workflow.api.DecisionHookTestResult;

/** What one synthetic decision hook call concluded (#945). */
public record DecisionHookTestResponse(
        DecisionHookOutcome outcome,
        DecisionHookFailure failure,
        Integer requestedApprovals,
        String reason,
        Integer httpStatus,
        long latencyMs) {

    public static DecisionHookTestResponse from(DecisionHookTestResult result) {
        return new DecisionHookTestResponse(result.outcome(), result.failure(),
                result.requestedApprovals(), result.reason(), result.httpStatus(),
                result.latencyMs());
    }
}
