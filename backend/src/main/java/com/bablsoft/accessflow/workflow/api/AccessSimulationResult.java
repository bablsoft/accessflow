package com.bablsoft.accessflow.workflow.api;

import com.bablsoft.accessflow.core.api.DecisionTraceStep;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.SimulationCaveat;

import java.util.List;

/**
 * The full journey of one hypothetical request (issue AF-859) — every stage from the submission
 * gate to execution-time masking, with what each one saw.
 *
 * @param evaluatedContext the routing signals the evaluation actually ran against, echoed because
 *                         half of routing debugging is a signal that was stale or absent rather
 *                         than a rule that was wrong. {@code null} when the trace never reached
 *                         routing.
 * @param caveats          the approximations that applied. A hypothetical request cannot carry
 *                         every signal a submitted one does, and a simulator that hides that is
 *                         worse than one that admits it.
 */
public record AccessSimulationResult(List<DecisionTraceStep> steps, QueryStatus resultingStatus,
                                     ConditionContext evaluatedContext,
                                     List<SimulationCaveat> caveats) {

    public AccessSimulationResult {
        steps = steps == null ? List.of() : List.copyOf(steps);
        caveats = caveats == null ? List.of() : List.copyOf(caveats);
    }
}
