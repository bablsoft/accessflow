package com.bablsoft.accessflow.apigov.internal.web;

import com.bablsoft.accessflow.apigov.api.ApiCallSimulationResult;
import com.bablsoft.accessflow.apigov.api.ApiDecisionStepKind;
import com.bablsoft.accessflow.core.api.DecisionTraceStep;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.SimulationCaveat;
import com.bablsoft.accessflow.core.api.StepOutcome;

import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * The API-call decision trace on the wire (issue AF-967). {@code resultingStatus} is null — and so
 * omitted — when the call would be refused before a status is ever assigned; the step carrying
 * {@code DENY} says which gate stopped it.
 */
record ApiCallSimulationResponse(List<Step> steps, QueryStatus resultingStatus,
                                 List<SimulationCaveat> caveats) {

    static ApiCallSimulationResponse from(ApiCallSimulationResult result,
                                          BiFunction<String, List<String>, String> reasonResolver) {
        return new ApiCallSimulationResponse(
                result.steps().stream().map(step -> Step.from(step, reasonResolver)).toList(),
                result.resultingStatus(),
                result.caveats());
    }

    record Step(ApiDecisionStepKind step, StepOutcome outcome, String reason,
                Map<String, Object> details) {

        /**
         * Narrows the trace's cross-kind {@code DecisionStepKind} back to this module's own enum, so
         * the OpenAPI schema keeps its enum constraint. Unreachable in practice — an API-call trace
         * only ever carries API-call stages — but a silent widening to a bare string would be worse.
         */
        static Step from(DecisionTraceStep step,
                         BiFunction<String, List<String>, String> reasonResolver) {
            if (!(step.step() instanceof ApiDecisionStepKind kind)) {
                throw new IllegalStateException(
                        "API call decision trace carried a foreign step kind: " + step.step().name());
            }
            return new Step(kind, step.outcome(),
                    reasonResolver.apply(step.reasonKey(), step.reasonArgs()), step.details());
        }
    }
}
