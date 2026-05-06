package com.partqam.accessflow.core.api;

import java.util.UUID;

/**
 * Cross-module read of the AI analysis attached to a query request: just enough fields
 * for downstream consumers (notifications, audit) to render risk badges and summaries.
 */
public record AiAnalysisSummaryView(
        UUID id,
        UUID queryRequestId,
        RiskLevel riskLevel,
        int riskScore,
        String summary) {
}
