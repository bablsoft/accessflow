package com.bablsoft.accessflow.workflow.internal;

import com.bablsoft.accessflow.workflow.api.QueryTemplateView;
import com.bablsoft.accessflow.workflow.internal.persistence.entity.QueryTemplateEntity;

import java.util.Arrays;
import java.util.List;

/** Maps {@link QueryTemplateEntity} rows into the public {@link QueryTemplateView} record. */
final class QueryTemplateMapper {

    private QueryTemplateMapper() {
    }

    static QueryTemplateView toView(QueryTemplateEntity entity, String ownerDisplayName) {
        List<String> tags = entity.getTags() == null
                ? List.of()
                : Arrays.stream(entity.getTags()).toList();
        return new QueryTemplateView(
                entity.getId(),
                entity.getOrganizationId(),
                entity.getOwnerId(),
                ownerDisplayName,
                entity.getDatasourceId(),
                entity.getName(),
                entity.getBody(),
                entity.getDescription(),
                tags,
                entity.getVisibility(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }
}
