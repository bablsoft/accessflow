package com.bablsoft.accessflow.access.internal.web;

import com.bablsoft.accessflow.access.api.AccessReviewService.PendingAccessRequest;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PendingAccessRequestItem(
        UUID id,
        DatasourceSummary datasource,
        RequesterSummary requestedBy,
        boolean canRead,
        boolean canWrite,
        boolean canDdl,
        List<String> allowedSchemas,
        List<String> allowedTables,
        String requestedDuration,
        String justification,
        int currentStage,
        Instant createdAt) {

    public static PendingAccessRequestItem from(PendingAccessRequest pending) {
        return new PendingAccessRequestItem(
                pending.id(),
                new DatasourceSummary(pending.datasourceId(), pending.datasourceName()),
                new RequesterSummary(pending.requesterId(), pending.requesterEmail()),
                pending.canRead(),
                pending.canWrite(),
                pending.canDdl(),
                pending.allowedSchemas(),
                pending.allowedTables(),
                pending.requestedDuration(),
                pending.justification(),
                pending.currentStage(),
                pending.createdAt());
    }

    public record DatasourceSummary(UUID id, String name) {
    }

    public record RequesterSummary(UUID id, String email) {
    }
}
