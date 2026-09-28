package com.bablsoft.accessflow.workflow.internal.hook;

import com.bablsoft.accessflow.workflow.api.DecisionHookFailure;
import com.bablsoft.accessflow.workflow.api.DecisionHookOutcome;

import java.util.UUID;

/**
 * The decision hook's answer for one query as the evaluator sees it (#945).
 *
 * @param outcome {@code null} when the hook applies but was not called — a simulation, which never
 *                calls out
 */
public record DecisionHookConsultation(UUID hookId, String hookName, DecisionHookOutcome outcome,
                                       DecisionHookFailure failure, Integer requestedApprovals,
                                       String reason, Integer httpStatus, long latencyMs) {

    /** A hook that applies but is not called, for the access simulator and the policy simulator. */
    public static DecisionHookConsultation simulated(UUID hookId, String hookName) {
        return new DecisionHookConsultation(hookId, hookName, null, null, null, null, null, 0L);
    }

    public boolean isSimulated() {
        return outcome == null;
    }

    public boolean isFailure() {
        return outcome == DecisionHookOutcome.FAILED;
    }
}
