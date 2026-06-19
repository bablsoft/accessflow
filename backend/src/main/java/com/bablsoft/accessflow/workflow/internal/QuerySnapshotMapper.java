package com.bablsoft.accessflow.workflow.internal;

import com.bablsoft.accessflow.workflow.api.QuerySnapshotView;
import com.bablsoft.accessflow.workflow.internal.persistence.entity.QuerySnapshotEntity;

import java.util.Arrays;
import java.util.List;

/** Maps {@link QuerySnapshotEntity} rows into the public {@link QuerySnapshotView}. */
final class QuerySnapshotMapper {

    private QuerySnapshotMapper() {
    }

    static QuerySnapshotView toView(QuerySnapshotEntity entity) {
        List<String> referenced = entity.getReferencedTables() == null
                ? List.of()
                : Arrays.stream(entity.getReferencedTables()).toList();
        return new QuerySnapshotView(
                entity.getId(),
                entity.getQueryRequestId(),
                entity.getOrganizationId(),
                entity.getDatasourceId(),
                entity.getSubmittedBy(),
                entity.getSqlText(),
                entity.getQueryType(),
                entity.isTransactional(),
                entity.getDbType(),
                referenced,
                entity.getSchemaHash(),
                entity.getAiAnalysisJson(),
                entity.getReviewDecisionsJson(),
                entity.getRowsAffected(),
                entity.getExecutionDurationMs(),
                entity.getExecutedAt(),
                entity.getCreatedAt());
    }
}
