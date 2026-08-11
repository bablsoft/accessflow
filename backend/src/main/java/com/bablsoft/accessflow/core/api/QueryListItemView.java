package com.bablsoft.accessflow.core.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Cross-module DTO for a row of {@code GET /queries}: enough fields for the list view's
 * table (status pill, risk pill, submitter chip, datasource name) without loading the full
 * SQL text or AI issue list. {@code recurring} is true for a recurring-series parent (#627);
 * {@code recurringParentId} is set on occurrence rows.
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
        boolean aiFailed,
        Instant scheduledFor,
        boolean recurring,
        UUID recurringParentId,
        Instant createdAt) {

    /** Backward-compatible constructor without the #627 recurrence fields (defaults to absent). */
    public QueryListItemView(UUID id, UUID datasourceId, String datasourceName,
                             UUID submittedByUserId, String submittedByEmail,
                             String submittedByDisplayName, QueryType queryType,
                             QueryStatus status, RiskLevel aiRiskLevel, Integer aiRiskScore,
                             boolean aiFailed, Instant scheduledFor, Instant createdAt) {
        this(id, datasourceId, datasourceName, submittedByUserId, submittedByEmail,
                submittedByDisplayName, queryType, status, aiRiskLevel, aiRiskScore, aiFailed,
                scheduledFor, false, null, createdAt);
    }
}
