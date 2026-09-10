package com.bablsoft.accessflow.apigov.internal.web;

import com.bablsoft.accessflow.apigov.api.ApiCallSimulationInput;
import com.bablsoft.accessflow.core.api.AiOutcome;
import com.bablsoft.accessflow.core.api.RiskLevel;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * One hypothetical API call to trace (issue AF-967).
 *
 * <p>{@code operationId} and {@code verb} are both optional: omitting both models a free-form call,
 * which the submit path accepts and always routes to review.
 */
record SimulateApiCallRequest(
        @NotNull(message = "{validation.api_call_simulation.user_id_required}") UUID userId,
        @NotNull(message = "{validation.api_call_simulation.connector_id_required}") UUID connectorId,
        @Size(max = 255, message = "{validation.api_call_simulation.operation_id_too_long}")
        String operationId,
        @Size(max = 20, message = "{validation.api_call_simulation.verb_too_long}") String verb,
        AiOutcome aiOutcome,
        RiskLevel riskLevel) {

    ApiCallSimulationInput toInput() {
        return new ApiCallSimulationInput(userId, connectorId, blankToNull(operationId),
                blankToNull(verb), aiOutcome, riskLevel);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
