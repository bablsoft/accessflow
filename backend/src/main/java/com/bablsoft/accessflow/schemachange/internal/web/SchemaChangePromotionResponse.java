package com.bablsoft.accessflow.schemachange.internal.web;

import com.bablsoft.accessflow.schemachange.api.SchemaChangePromotionStatus;
import com.bablsoft.accessflow.schemachange.api.SchemaChangePromotionView;

import java.time.Instant;
import java.util.UUID;

public record SchemaChangePromotionResponse(
        UUID id,
        UUID changeSetId,
        UUID environmentId,
        String environmentName,
        UUID datasourceId,
        UUID requestGroupId,
        SchemaChangePromotionStatus status,
        String statementsChecksum,
        UUID promotedBy,
        Instant submittedAt,
        Instant appliedAt,
        String errorMessage,
        String schemaSnapshot,
        Instant snapshotTakenAt) {

    static SchemaChangePromotionResponse from(SchemaChangePromotionView view) {
        return new SchemaChangePromotionResponse(view.id(), view.changeSetId(), view.environmentId(),
                view.environmentName(), view.datasourceId(), view.requestGroupId(), view.status(),
                view.statementsChecksum(), view.promotedBy(), view.submittedAt(), view.appliedAt(),
                view.errorMessage(), view.schemaSnapshot(), view.snapshotTakenAt());
    }
}
