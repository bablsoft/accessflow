package com.bablsoft.accessflow.workflow.internal.persistence.entity;

import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.QueryType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class QuerySnapshotEntityTest {

    @Test
    void defaultsAreSafe() {
        var entity = new QuerySnapshotEntity();

        assertThat(entity.getReferencedTables()).isEmpty();
        assertThat(entity.getReviewDecisionsJson()).isEqualTo("[]");
        assertThat(entity.isTransactional()).isFalse();
        assertThat(entity.getCreatedAt()).isNotNull();
    }

    @Test
    void gettersReturnSetValues() {
        var entity = new QuerySnapshotEntity();
        var id = UUID.randomUUID();
        var queryId = UUID.randomUUID();
        var orgId = UUID.randomUUID();
        var dsId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        var executedAt = Instant.parse("2026-06-18T10:15:30Z");

        entity.setId(id);
        entity.setQueryRequestId(queryId);
        entity.setOrganizationId(orgId);
        entity.setDatasourceId(dsId);
        entity.setSubmittedBy(userId);
        entity.setSqlText("SELECT 1");
        entity.setQueryType(QueryType.SELECT);
        entity.setTransactional(true);
        entity.setDbType(DbType.POSTGRESQL);
        entity.setReferencedTables(new String[]{"public.users"});
        entity.setSchemaHash("abc");
        entity.setAiAnalysisJson("{\"risk\":1}");
        entity.setReviewDecisionsJson("[{\"stage\":1}]");
        entity.setRowsAffected(5L);
        entity.setExecutionDurationMs(42);
        entity.setExecutedAt(executedAt);

        assertThat(entity.getId()).isEqualTo(id);
        assertThat(entity.getQueryRequestId()).isEqualTo(queryId);
        assertThat(entity.getOrganizationId()).isEqualTo(orgId);
        assertThat(entity.getDatasourceId()).isEqualTo(dsId);
        assertThat(entity.getSubmittedBy()).isEqualTo(userId);
        assertThat(entity.getSqlText()).isEqualTo("SELECT 1");
        assertThat(entity.getQueryType()).isEqualTo(QueryType.SELECT);
        assertThat(entity.isTransactional()).isTrue();
        assertThat(entity.getDbType()).isEqualTo(DbType.POSTGRESQL);
        assertThat(entity.getReferencedTables()).containsExactly("public.users");
        assertThat(entity.getSchemaHash()).isEqualTo("abc");
        assertThat(entity.getAiAnalysisJson()).isEqualTo("{\"risk\":1}");
        assertThat(entity.getReviewDecisionsJson()).isEqualTo("[{\"stage\":1}]");
        assertThat(entity.getRowsAffected()).isEqualTo(5L);
        assertThat(entity.getExecutionDurationMs()).isEqualTo(42);
        assertThat(entity.getExecutedAt()).isEqualTo(executedAt);
    }
}
