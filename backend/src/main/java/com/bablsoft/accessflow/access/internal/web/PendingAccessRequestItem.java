package com.bablsoft.accessflow.access.internal.web;

import com.bablsoft.accessflow.access.api.AccessResourceKind;
import com.bablsoft.accessflow.access.api.AccessReviewService.PendingAccessRequest;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PendingAccessRequestItem(
        UUID id,
        AccessResourceKind resourceKind,
        DatasourceSummary datasource,
        ConnectorSummary connector,
        RequesterSummary requestedBy,
        boolean canRead,
        boolean canWrite,
        boolean canDdl,
        List<String> allowedSchemas,
        List<String> allowedTables,
        List<String> allowedOperations,
        String requestedDuration,
        String justification,
        boolean preApproveQueries,
        int currentStage,
        Instant createdAt) {

    public static PendingAccessRequestItem from(PendingAccessRequest pending) {
        return new PendingAccessRequestItem(
                pending.id(),
                pending.resourceKind(),
                pending.datasourceId() == null ? null
                        : new DatasourceSummary(pending.datasourceId(), pending.datasourceName()),
                pending.connectorId() == null ? null
                        : new ConnectorSummary(pending.connectorId(), pending.connectorName()),
                new RequesterSummary(pending.requesterId(), pending.requesterEmail()),
                pending.canRead(),
                pending.canWrite(),
                pending.canDdl(),
                pending.allowedSchemas(),
                pending.allowedTables(),
                pending.allowedOperations(),
                pending.requestedDuration(),
                pending.justification(),
                pending.preApproveQueries(),
                pending.currentStage(),
                pending.createdAt());
    }

    public record DatasourceSummary(UUID id, String name) {
    }

    public record ConnectorSummary(UUID id, String name) {
    }

    public record RequesterSummary(UUID id, String email) {
    }
}
