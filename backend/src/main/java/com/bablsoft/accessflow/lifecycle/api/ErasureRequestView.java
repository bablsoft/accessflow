package com.bablsoft.accessflow.lifecycle.api;

import java.time.Instant;
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
}
