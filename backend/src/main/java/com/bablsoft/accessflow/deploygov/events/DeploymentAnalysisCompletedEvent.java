package com.bablsoft.accessflow.deploygov.events;

import com.bablsoft.accessflow.core.api.RiskLevel;

import java.util.UUID;

/** AI analysis of a deployment finished and the {@code ai_analyses} row was persisted. */
public record DeploymentAnalysisCompletedEvent(
        UUID deploymentRequestId, UUID aiAnalysisId, RiskLevel riskLevel, int riskScore,
        String summary) {
}
