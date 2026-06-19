package com.bablsoft.accessflow.workflow.api;

import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.QueryType;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Read-side view of an immutable {@code query_snapshots} row (AF-449). The AI analysis and review
 * decisions are carried as raw JSON strings (as {@code QueryDetailView} does for {@code issuesJson}) so
 * this api type stays free of any third-party dependency. {@code referencedTables} is the set of tables
 * the snapshotted query touched, used by the replay schema-compatibility gate.
 */
public record QuerySnapshotView(
        UUID id,
        UUID queryRequestId,
        UUID organizationId,
        UUID datasourceId,
        UUID submittedBy,
        String sqlText,
        QueryType queryType,
        boolean transactional,
        DbType dbType,
        List<String> referencedTables,
        String schemaHash,
        String aiAnalysisJson,
        String reviewDecisionsJson,
        Long rowsAffected,
        Integer executionDurationMs,
        Instant executedAt,
        Instant createdAt) {

    public QuerySnapshotView {
        referencedTables = referencedTables == null ? List.of() : List.copyOf(referencedTables);
    }
}
