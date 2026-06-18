package com.bablsoft.accessflow.workflow.internal;

import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.workflow.internal.persistence.entity.QuerySnapshotEntity;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class QuerySnapshotMapperTest {

    private QuerySnapshotEntity entity() {
        var entity = new QuerySnapshotEntity();
        entity.setId(UUID.randomUUID());
        entity.setQueryRequestId(UUID.randomUUID());
        entity.setOrganizationId(UUID.randomUUID());
        entity.setDatasourceId(UUID.randomUUID());
        entity.setSubmittedBy(UUID.randomUUID());
        entity.setSqlText("SELECT 1");
        entity.setQueryType(QueryType.SELECT);
        entity.setTransactional(true);
        entity.setDbType(DbType.POSTGRESQL);
        entity.setReferencedTables(new String[]{"public.users", "public.orders"});
        entity.setSchemaHash("hash");
        entity.setAiAnalysisJson("{\"risk\":\"LOW\"}");
        entity.setReviewDecisionsJson("[{\"stage\":1}]");
        entity.setRowsAffected(7L);
        entity.setExecutionDurationMs(15);
        entity.setExecutedAt(Instant.parse("2026-06-18T10:00:00Z"));
        return entity;
    }

    @Test
    void mapsAllFields() {
        var entity = entity();

        var view = QuerySnapshotMapper.toView(entity);

        assertThat(view.id()).isEqualTo(entity.getId());
        assertThat(view.queryRequestId()).isEqualTo(entity.getQueryRequestId());
        assertThat(view.organizationId()).isEqualTo(entity.getOrganizationId());
        assertThat(view.datasourceId()).isEqualTo(entity.getDatasourceId());
        assertThat(view.submittedBy()).isEqualTo(entity.getSubmittedBy());
        assertThat(view.sqlText()).isEqualTo("SELECT 1");
        assertThat(view.queryType()).isEqualTo(QueryType.SELECT);
        assertThat(view.transactional()).isTrue();
        assertThat(view.dbType()).isEqualTo(DbType.POSTGRESQL);
        assertThat(view.referencedTables()).containsExactly("public.users", "public.orders");
        assertThat(view.schemaHash()).isEqualTo("hash");
        assertThat(view.aiAnalysisJson()).isEqualTo("{\"risk\":\"LOW\"}");
        assertThat(view.reviewDecisionsJson()).isEqualTo("[{\"stage\":1}]");
        assertThat(view.rowsAffected()).isEqualTo(7L);
        assertThat(view.executionDurationMs()).isEqualTo(15);
        assertThat(view.executedAt()).isEqualTo(Instant.parse("2026-06-18T10:00:00Z"));
    }

    @Test
    void handlesNullReferencedTablesAndNullAiAnalysis() {
        var entity = entity();
        entity.setReferencedTables(null);
        entity.setAiAnalysisJson(null);

        var view = QuerySnapshotMapper.toView(entity);

        assertThat(view.referencedTables()).isEmpty();
        assertThat(view.aiAnalysisJson()).isNull();
    }
}
