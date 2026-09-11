package com.bablsoft.accessflow.deploygov.api;

import com.bablsoft.accessflow.core.api.DecisionTraceStep;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.SimulationCaveat;

import java.time.Instant;
import java.util.List;

/**
 * The full journey of one hypothetical deployment (issue AF-967) — every stage from the CI trigger to
 * the release gate, with what each one saw.
 *
 * @param resultingStatus the status the deployment would end in, or {@code null} when it would be
 *                        refused before a status is ever assigned
 * @param releasable      what {@code GET /deployment-gate} would answer at {@code evaluatedAt}. False
 *                        for anything short of an approved, unfrozen, due release — which is the
 *                        common case in a trace and is not an error
 * @param evaluatedAt     the instant the trace was evaluated at, echoed because it is an input the
 *                        caller may have chosen
 */
public record DeploymentSimulationResult(List<DecisionTraceStep> steps, QueryStatus resultingStatus,
                                         boolean releasable, Instant evaluatedAt,
                                         List<SimulationCaveat> caveats) {

    public DeploymentSimulationResult {
        steps = steps == null ? List.of() : List.copyOf(steps);
        caveats = caveats == null ? List.of() : List.copyOf(caveats);
    }
}
