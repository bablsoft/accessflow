package com.bablsoft.accessflow.apigov.api;

import com.bablsoft.accessflow.core.api.DecisionTraceStep;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.SimulationCaveat;

import java.util.List;

/**
 * The full journey of one hypothetical API call (issue AF-967) — every stage from the submission gate
 * to execution-time response masking, with what each one saw.
 *
 * @param resultingStatus the status the call would end in, or {@code null} when it would be refused
 *                        before a status is ever assigned
 * @param caveats         the approximations that applied. A hypothetical call cannot carry every
 *                        signal a submitted one does, and a simulator that hides that is worse than
 *                        one that admits it.
 */
public record ApiCallSimulationResult(List<DecisionTraceStep> steps, QueryStatus resultingStatus,
                                      List<SimulationCaveat> caveats) {

    public ApiCallSimulationResult {
        steps = steps == null ? List.of() : List.copyOf(steps);
        caveats = caveats == null ? List.of() : List.copyOf(caveats);
    }
}
