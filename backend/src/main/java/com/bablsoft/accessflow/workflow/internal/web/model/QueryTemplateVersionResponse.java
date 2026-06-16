package com.bablsoft.accessflow.workflow.internal.web.model;

import com.bablsoft.accessflow.workflow.api.QueryTemplateChangeType;
import com.bablsoft.accessflow.workflow.api.QueryTemplateVersionView;
import com.bablsoft.accessflow.workflow.api.QueryTemplateVisibility;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record QueryTemplateVersionResponse(
        UUID id,
        UUID templateId,
        int versionNumber,
        UUID datasourceId,
        String name,
        String body,
        String description,
        List<String> tags,
        QueryTemplateVisibility visibility,
        QueryTemplateChangeType changeType,
        UUID authorId,
        String authorDisplayName,
        Instant createdAt
) {
    public static QueryTemplateVersionResponse from(QueryTemplateVersionView view) {
        return new QueryTemplateVersionResponse(
                view.id(),
                view.templateId(),
                view.versionNumber(),
                view.datasourceId(),
                view.name(),
                view.body(),
                view.description(),
                view.tags(),
                view.visibility(),
                view.changeType(),
                view.authorId(),
                view.authorDisplayName(),
                view.createdAt());
    }
}
