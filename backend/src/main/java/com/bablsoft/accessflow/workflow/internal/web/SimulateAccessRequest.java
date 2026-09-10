package com.bablsoft.accessflow.workflow.internal.web;

import com.bablsoft.accessflow.core.api.RiskLevel;
import com.bablsoft.accessflow.workflow.api.AccessSimulationInput;
import com.bablsoft.accessflow.workflow.api.AiOutcome;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * A hypothetical request to trace (AF-859). {@code risk_level} / {@code risk_score} are the supplied
 * verdict, not a computed one — the simulator never calls the AI provider.
 */
record SimulateAccessRequest(
        @NotNull(message = "{validation.access_simulation.user_id_required}") UUID userId,
        @NotNull(message = "{validation.access_simulation.datasource_id_required}") UUID datasourceId,
        @NotBlank(message = "{validation.access_simulation.sql_required}")
        @Size(max = 100000, message = "{validation.access_simulation.sql_too_long}") String sql,
        AiOutcome aiOutcome,
        RiskLevel riskLevel,
        @Min(value = -1, message = "{validation.access_simulation.risk_score_range}")
        @Max(value = 100, message = "{validation.access_simulation.risk_score_range}")
        Integer riskScore) {

    AccessSimulationInput toInput() {
        return new AccessSimulationInput(userId, datasourceId, sql, aiOutcome, riskLevel, riskScore);
    }
}
