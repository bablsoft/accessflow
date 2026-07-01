package com.bablsoft.accessflow.lifecycle.internal.web;

import com.bablsoft.accessflow.lifecycle.api.ErasureConditionSet;
import com.bablsoft.accessflow.lifecycle.api.ErasureRequestView;
import com.bablsoft.accessflow.lifecycle.api.ErasureStatus;
import com.bablsoft.accessflow.lifecycle.api.LifecycleSubjectType;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** API response for a right-to-erasure request. */
public record ErasureRequestResponse(
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

    static ErasureRequestResponse from(ErasureRequestView v) {
        return new ErasureRequestResponse(v.id(), v.organizationId(), v.datasourceId(),
                v.datasourceName(), v.subjectType(), v.subjectIdentifier(), v.targetTable(),
                v.targetColumns(), v.conditions(), v.rawWhere(), v.status(), v.reason(),
                v.requestedBy(), v.requestedByEmail(), v.aiScopeAnalysisId(), v.scopeSnapshot(),
                v.estimatedRows(), v.affectedRows(), v.executedAt(), v.failureReason(),
                v.createdAt(), v.updatedAt());
    }
}
