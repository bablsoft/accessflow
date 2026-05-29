package com.bablsoft.accessflow.workflow.internal.web.model;

import com.bablsoft.accessflow.workflow.api.QueryTemplateView;
import com.bablsoft.accessflow.workflow.api.QueryTemplateVisibility;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record QueryTemplateResponse(
        UUID id,
        UUID organizationId,
        UUID ownerId,
        String ownerDisplayName,
        UUID datasourceId,
        String name,
        String body,
        String description,
        List<String> tags,
        QueryTemplateVisibility visibility,
        boolean editable,
        Instant createdAt,
        Instant updatedAt
) {
    public static QueryTemplateResponse from(QueryTemplateView view, UUID callerUserId) {
        boolean isOwner = view.ownerId() != null && view.ownerId().equals(callerUserId);
        return new QueryTemplateResponse(
                view.id(),
                view.organizationId(),
                view.ownerId(),
                view.ownerDisplayName(),
                view.datasourceId(),
                view.name(),
                view.body(),
                view.description(),
                view.tags(),
                view.visibility(),
                isOwner,
                view.createdAt(),
                view.updatedAt());
    }
}
