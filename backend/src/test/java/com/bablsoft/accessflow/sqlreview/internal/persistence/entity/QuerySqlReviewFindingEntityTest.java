package com.bablsoft.accessflow.sqlreview.internal.persistence.entity;

import com.bablsoft.accessflow.sqlreview.api.SqlReviewSeverity;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class QuerySqlReviewFindingEntityTest {

    @Test
    void gettersReturnSetValues() {
        var entity = new QuerySqlReviewFindingEntity();
        var id = UUID.randomUUID();
        var queryId = UUID.randomUUID();
        var createdAt = Instant.parse("2026-09-01T10:00:00Z");

        entity.setId(id);
        entity.setQueryRequestId(queryId);
        entity.setRuleId("missing_where_on_delete");
        entity.setSeverity(SqlReviewSeverity.WARN);
        entity.setStatementIndex(1);
        entity.setLineNumber(4);
        entity.setArgs("{\"table\":\"users\"}");
        entity.setCreatedAt(createdAt);

        assertThat(entity.getId()).isEqualTo(id);
        assertThat(entity.getQueryRequestId()).isEqualTo(queryId);
        assertThat(entity.getRuleId()).isEqualTo("missing_where_on_delete");
        assertThat(entity.getSeverity()).isEqualTo(SqlReviewSeverity.WARN);
        assertThat(entity.getStatementIndex()).isEqualTo(1);
        assertThat(entity.getLineNumber()).isEqualTo(4);
        assertThat(entity.getArgs()).isEqualTo("{\"table\":\"users\"}");
        assertThat(entity.getCreatedAt()).isEqualTo(createdAt);
    }

    @Test
    void defaultsMatchTheDdl() {
        var entity = new QuerySqlReviewFindingEntity();

        assertThat(entity.getStatementIndex()).isZero();
        assertThat(entity.getLineNumber()).isNull();
        assertThat(entity.getArgs()).isNull();
        assertThat(entity.getCreatedAt()).isNotNull();
    }
}
