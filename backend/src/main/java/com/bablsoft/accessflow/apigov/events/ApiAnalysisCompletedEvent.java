package com.bablsoft.accessflow.apigov.events;

import com.bablsoft.accessflow.core.api.RiskLevel;

import java.util.UUID;

/** Published when AI analysis of an API request succeeds; consumed by the review state machine. */
public record ApiAnalysisCompletedEvent(
        UUID apiRequestId, UUID aiAnalysisId, RiskLevel riskLevel, int riskScore, String summary) {
}
