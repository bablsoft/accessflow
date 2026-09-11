package com.bablsoft.accessflow.deploygov.api;

import com.bablsoft.accessflow.core.api.AiOutcome;
import com.bablsoft.accessflow.core.api.RiskLevel;

import java.time.Instant;
import java.util.UUID;

/**
 * One hypothetical deployment to trace through the live evaluators and the release gate (issue
 * AF-967).
 *
 * <p>{@code riskLevel} is the <em>supplied</em> AI verdict, never a computed one: calling the provider
 * would spend the organization's AI budget against the AF-55 guardrails and make the endpoint
 * non-deterministic. It is read only when {@code aiOutcome} is {@link AiOutcome#COMPLETED}. There is
 * deliberately no risk <em>score</em>: deployment routing gates on {@code minRiskLevel} alone.
 *
 * @param scheduledFor the hypothetical deferred-release moment, compared against {@code at}
 * @param at           the instant the whole trace is evaluated at, defaulting to now. This is the one
 *                     input the query explainer has no equivalent for, and it exists because the two
 *                     questions a deployment admin most needs to ask are time-shaped: is Friday
 *                     evening inside a freeze window, and would this policy's maintenance hour match.
 *                     Both the freeze evaluator and the routing engine already take an explicit
 *                     instant, so it is handed to the real ones rather than modelled.
 */
public record DeploymentSimulationInput(UUID userId, UUID pipelineId, UUID environmentId,
                                        String version, AiOutcome aiOutcome, RiskLevel riskLevel,
                                        Instant scheduledFor, Instant at) {

    public DeploymentSimulationInput {
        aiOutcome = aiOutcome == null ? AiOutcome.SKIPPED : aiOutcome;
    }
}
