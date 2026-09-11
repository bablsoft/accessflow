package com.bablsoft.accessflow.workflow.api;

import com.bablsoft.accessflow.core.api.AiOutcome;
import com.bablsoft.accessflow.core.api.RiskLevel;

import java.util.UUID;

/**
 * One hypothetical request to trace through the live evaluators (issue AF-859).
 *
 * <p>{@code riskLevel} / {@code riskScore} are the <em>supplied</em> AI verdict, never a computed
 * one: calling the provider would spend the organization's AI budget against the AF-55 guardrails
 * and make the endpoint non-deterministic. They are read only when {@code aiOutcome} is
 * {@link AiOutcome#COMPLETED}, because that is the only branch production ever sees a verdict on.
 *
 * @param riskScore {@code null} is treated as {@code -1}, the same "absent" sentinel the live
 *                  completion event uses; a level without a score suppresses the grant fast path
 *                  while leaving every score-based routing condition unmatched
 */
public record AccessSimulationInput(UUID userId, UUID datasourceId, String sql, AiOutcome aiOutcome,
                                    RiskLevel riskLevel, Integer riskScore) {

    public AccessSimulationInput {
        aiOutcome = aiOutcome == null ? AiOutcome.SKIPPED : aiOutcome;
    }

    public int effectiveRiskScore() {
        return riskScore == null ? -1 : riskScore;
    }
}
