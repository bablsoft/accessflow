package com.bablsoft.accessflow.core.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Cross-module DTO for a row of {@code GET /queries}: enough fields for the list view's
 * table (status pill, risk pill, submitter chip, datasource name) without loading the full
 * SQL text or AI issue list.
 */
public record QueryListItemView(
        UUID id,
        UUID datasourceId,
        String datasourceName,
        UUID submittedByUserId,
        String submittedByEmail,
        String submittedByDisplayName,
        QueryType queryType,
        QueryStatus status,
        RiskLevel aiRiskLevel,
        Integer aiRiskScore,
        Instant createdAt) {
}
