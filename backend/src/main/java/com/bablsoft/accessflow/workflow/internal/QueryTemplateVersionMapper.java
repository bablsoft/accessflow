package com.bablsoft.accessflow.workflow.internal;

import com.bablsoft.accessflow.workflow.api.QueryTemplateVersionView;
import com.bablsoft.accessflow.workflow.internal.persistence.entity.QueryTemplateVersionEntity;

import java.util.Arrays;
import java.util.List;

/** Maps {@link QueryTemplateVersionEntity} rows into the public {@link QueryTemplateVersionView}. */
final class QueryTemplateVersionMapper {

    private QueryTemplateVersionMapper() {
    }

    static QueryTemplateVersionView toView(QueryTemplateVersionEntity entity, String authorDisplayName) {
        List<String> tags = entity.getTags() == null
                ? List.of()
                : Arrays.stream(entity.getTags()).toList();
        return new QueryTemplateVersionView(
                entity.getId(),
                entity.getTemplateId(),
                entity.getVersionNumber(),
                entity.getDatasourceId(),
                entity.getName(),
                entity.getBody(),
                entity.getDescription(),
                tags,
                entity.getVisibility(),
                entity.getChangeType(),
                entity.getAuthorId(),
                authorDisplayName,
                entity.getCreatedAt());
    }
}
