package com.bablsoft.accessflow.apigov.api;

import com.bablsoft.accessflow.core.api.AiOutcome;
import com.bablsoft.accessflow.core.api.RiskLevel;

import java.util.UUID;

/**
 * One hypothetical API call to trace through the live evaluators (issue AF-967).
 *
 * <p>{@code riskLevel} is the <em>supplied</em> AI verdict, never a computed one: calling the provider
 * would spend the organization's AI budget against the AF-55 guardrails and make the endpoint
 * non-deterministic. It is read only when {@code aiOutcome} is {@link AiOutcome#COMPLETED}, because
 * that is the only branch production ever sees a verdict on.
 *
 * <p>There is deliberately no risk <em>score</em>: API routing conditions gate on {@code minRiskLevel}
 * alone, so accepting one would imply a precision the evaluator does not have.
 *
 * @param operationId the schema operation to model, or {@code null} for a free-form call
 * @param verb        the HTTP verb, used by the classifier only when no schema operation matches
 */
public record ApiCallSimulationInput(UUID userId, UUID connectorId, String operationId, String verb,
                                     AiOutcome aiOutcome, RiskLevel riskLevel) {

    public ApiCallSimulationInput {
        aiOutcome = aiOutcome == null ? AiOutcome.SKIPPED : aiOutcome;
    }
}
