package com.bablsoft.accessflow.core.events;

import com.bablsoft.accessflow.core.api.RiskLevel;

import java.util.UUID;

/**
 * Published after a successful AI analysis run. Consumed by the workflow state machine to
 * progress the query request out of {@code PENDING_AI} and to feed risk signals into the
 * routing-policy engine. {@code riskScore} is the 0–100 AI score; the legacy 3-arg constructor
 * defaults it to {@code -1} for callers (and tests) that don't supply one.
 */
public record AiAnalysisCompletedEvent(UUID queryRequestId, UUID aiAnalysisId, RiskLevel riskLevel,
                                       int riskScore) {

    public AiAnalysisCompletedEvent(UUID queryRequestId, UUID aiAnalysisId, RiskLevel riskLevel) {
        this(queryRequestId, aiAnalysisId, riskLevel, -1);
    }
}
