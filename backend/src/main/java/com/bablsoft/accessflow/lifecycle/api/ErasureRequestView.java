package com.bablsoft.accessflow.lifecycle.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Read view of a right-to-erasure (deletion) request, enriched with the datasource name and
 * requester email for display.
 */
public record ErasureRequestView(
        UUID id,
        UUID organizationId,
        UUID datasourceId,
        String datasourceName,
        LifecycleSubjectType subjectType,
        String subjectIdentifier,
        String targetTable,
        List<String> targetColumns,
        ErasureConditionSet conditions,
        String rawWhere,
        ErasureStatus status,
        String reason,
        UUID requestedBy,
        String requestedByEmail,
        UUID aiScopeAnalysisId,
        String scopeSnapshot,
        Long estimatedRows,
        Long affectedRows,
        Instant executedAt,
        String failureReason,
        Instant createdAt,
        Instant updatedAt) {

    public ErasureRequestView {
        targetColumns = targetColumns == null ? List.of() : List.copyOf(targetColumns);
    }
}
