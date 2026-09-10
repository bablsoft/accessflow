package com.bablsoft.accessflow.deploygov.internal.web;

import com.bablsoft.accessflow.core.api.DecisionTraceStep;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.SimulationCaveat;
import com.bablsoft.accessflow.core.api.StepOutcome;
import com.bablsoft.accessflow.deploygov.api.DeploymentDecisionStepKind;
import com.bablsoft.accessflow.deploygov.api.DeploymentSimulationResult;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * The deployment decision trace on the wire (issue AF-967). {@code resultingStatus} is null — and so
 * omitted — when the deployment would be refused before a status is ever assigned; the step carrying
 * {@code DENY} says which gate stopped it. {@code releasable} is what the CI job's gate poll would
 * answer at {@code evaluatedAt}.
 */
record DeploymentSimulationResponse(List<Step> steps, QueryStatus resultingStatus,
                                    boolean releasable, Instant evaluatedAt,
                                    List<SimulationCaveat> caveats) {

    static DeploymentSimulationResponse from(DeploymentSimulationResult result,
                                             BiFunction<String, List<String>, String> reasonResolver) {
        return new DeploymentSimulationResponse(
                result.steps().stream().map(step -> Step.from(step, reasonResolver)).toList(),
                result.resultingStatus(), result.releasable(), result.evaluatedAt(),
                result.caveats());
    }

    record Step(DeploymentDecisionStepKind step, StepOutcome outcome, String reason,
                Map<String, Object> details) {

        /**
         * Narrows the trace's cross-kind {@code DecisionStepKind} back to this module's own enum, so
         * the OpenAPI schema keeps its enum constraint. Unreachable in practice — a deployment trace
         * only ever carries deployment stages — but a silent widening to a bare string would be worse.
         */
        static Step from(DecisionTraceStep step,
                         BiFunction<String, List<String>, String> reasonResolver) {
            if (!(step.step() instanceof DeploymentDecisionStepKind kind)) {
                throw new IllegalStateException(
                        "Deployment decision trace carried a foreign step kind: "
                                + step.step().name());
            }
            return new Step(kind, step.outcome(),
                    reasonResolver.apply(step.reasonKey(), step.reasonArgs()), step.details());
        }
    }
}
