package com.partqam.accessflow.core.events;

import com.partqam.accessflow.core.api.RiskLevel;

import java.util.UUID;

/**
 * Published after a successful AI analysis run. Consumed by the workflow state machine to
 * progress the query request out of {@code PENDING_AI}.
 */
public record AiAnalysisCompletedEvent(UUID queryRequestId, UUID aiAnalysisId, RiskLevel riskLevel) {
}
