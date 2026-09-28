package com.bablsoft.accessflow.workflow.internal.hook;

import com.bablsoft.accessflow.workflow.api.DecisionHookFailure;
import com.bablsoft.accessflow.workflow.api.DecisionHookOutcome;

import java.time.Instant;
import java.util.UUID;

/** What the decision hook answered for one query (#945), for the query-detail read path. */
public record DecisionHookResultView(UUID decisionHookId, String decisionHookName,
                                     DecisionHookOutcome outcome, DecisionHookFailure failure,
                                     Integer requestedApprovals, String reason, Integer httpStatus,
                                     long latencyMs, Instant evaluatedAt) {
}
