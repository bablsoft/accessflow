package com.bablsoft.accessflow.workflow.internal.web;

import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.RiskLevel;
import com.bablsoft.accessflow.core.api.SimulationCaveat;
import com.bablsoft.accessflow.workflow.api.AccessSimulationResult;
import com.bablsoft.accessflow.workflow.api.ConditionContext;
import com.bablsoft.accessflow.workflow.api.QueryDecisionStepKind;
import com.bablsoft.accessflow.core.api.DecisionTraceStep;
import com.bablsoft.accessflow.core.api.StepOutcome;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;

/**
 * The wire form of an access simulation (AF-859).
 *
 * <p>Trace steps carry a message key and arguments rather than text, because the evaluator that
 * produces them also runs on an asynchronous path with no request locale. Resolution happens here,
 * against the caller's {@code Accept-Language}.
 *
 * @param resultingStatus {@code null} when the request would be rejected before a status is ever
 *                        assigned — the denying step says which gate stopped it. Serialization
 *                        omits null fields, so a client sees the key absent rather than null.
 */
record AccessSimulationResponse(List<Step> steps, QueryStatus resultingStatus,
                                EvaluatedContext evaluatedContext, List<SimulationCaveat> caveats) {

    /** @param reasonResolver renders a step's message key and arguments in the caller's locale */
    static AccessSimulationResponse from(AccessSimulationResult result,
                                         BiFunction<String, List<String>, String> reasonResolver) {
        return new AccessSimulationResponse(
                result.steps().stream().map(step -> Step.from(step, reasonResolver)).toList(),
                result.resultingStatus(),
                EvaluatedContext.from(result.evaluatedContext()),
                result.caveats());
    }

    record Step(QueryDecisionStepKind step, StepOutcome outcome, String reason,
                Map<String, Object> details) {

        /**
         * Narrows the trace's cross-kind {@code DecisionStepKind} back to this module's own enum, so
         * the OpenAPI schema keeps its enum constraint. Unreachable in practice — a query trace only
         * ever carries query stages — but a silent widening to a bare string would be worse.
         */
        static Step from(DecisionTraceStep step,
                         BiFunction<String, List<String>, String> reasonResolver) {
            if (!(step.step() instanceof QueryDecisionStepKind kind)) {
                throw new IllegalStateException(
                        "Query decision trace carried a foreign step kind: " + step.step().name());
            }
            return new Step(kind, step.outcome(),
                    reasonResolver.apply(step.reasonKey(), step.reasonArgs()), step.details());
        }
    }

    /** The routing signals the evaluation saw, echoed so a stale or absent one is visible. */
    record EvaluatedContext(com.bablsoft.accessflow.core.api.QueryType queryType,
                            List<String> referencedTables, RiskLevel riskLevel, int riskScore,
                            String requesterRoleName, List<UUID> requesterGroupIds,
                            LocalDateTime evaluatedAt, boolean hasWhereClause,
                            boolean hasLimitClause, boolean transactional,
                            String requesterIpAddress, String requesterUserAgent,
                            boolean ciCdOrigin, Integer minutesSinceLastApproval,
                            boolean anomalyActive, Long estimatedRows, String scanType) {

        static EvaluatedContext from(ConditionContext context) {
            if (context == null) {
                return null;
            }
            return new EvaluatedContext(context.queryType(),
                    List.copyOf(context.referencedTables()), context.riskLevel(),
                    context.riskScore(), context.requesterRoleName(),
                    List.copyOf(context.requesterGroupIds()), context.evaluatedAt(),
                    context.hasWhereClause(), context.hasLimitClause(), context.transactional(),
                    context.requesterIpAddress(), context.requesterUserAgent(),
                    context.ciCdOrigin(), context.minutesSinceLastApproval(),
                    context.anomalyActive(), context.estimatedRows(), context.scanType());
        }
    }
}
