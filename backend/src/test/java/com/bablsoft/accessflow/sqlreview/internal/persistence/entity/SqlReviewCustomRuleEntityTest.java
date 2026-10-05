package com.bablsoft.accessflow.sqlreview.internal.persistence.entity;

import com.bablsoft.accessflow.sqlreview.api.SqlReviewSeverity;
import com.bablsoft.accessflow.sqlreview.api.SqlRuleCategory;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SqlReviewCustomRuleEntityTest {

    @Test
    void gettersReturnSetValues() {
        var entity = new SqlReviewCustomRuleEntity();
        var id = UUID.randomUUID();
        var orgId = UUID.randomUUID();
        var createdAt = Instant.parse("2026-10-01T10:00:00Z");
        var updatedAt = Instant.parse("2026-10-02T10:00:00Z");

        entity.setId(id);
        entity.setOrganizationId(orgId);
        entity.setRuleId("custom_no_dblink");
        entity.setName("No dblink");
        entity.setDescription("Cross-database calls bypass review");
        entity.setMessage("dblink on {tables}");
        entity.setCategory(SqlRuleCategory.STATEMENT_SAFETY);
        entity.setDefaultSeverity(SqlReviewSeverity.BLOCK);
        entity.setCondition("{\"type\":\"function_called\",\"names\":[\"dblink\"]}");
        entity.setEnabled(false);
        entity.setVersion(2L);
        entity.setCreatedAt(createdAt);
        entity.setUpdatedAt(updatedAt);

        assertThat(entity.getId()).isEqualTo(id);
        assertThat(entity.getOrganizationId()).isEqualTo(orgId);
        assertThat(entity.getRuleId()).isEqualTo("custom_no_dblink");
        assertThat(entity.getName()).isEqualTo("No dblink");
        assertThat(entity.getDescription()).isEqualTo("Cross-database calls bypass review");
        assertThat(entity.getMessage()).isEqualTo("dblink on {tables}");
        assertThat(entity.getCategory()).isEqualTo(SqlRuleCategory.STATEMENT_SAFETY);
        assertThat(entity.getDefaultSeverity()).isEqualTo(SqlReviewSeverity.BLOCK);
        assertThat(entity.getCondition()).contains("dblink");
        assertThat(entity.isEnabled()).isFalse();
        assertThat(entity.getVersion()).isEqualTo(2L);
        assertThat(entity.getCreatedAt()).isEqualTo(createdAt);
        assertThat(entity.getUpdatedAt()).isEqualTo(updatedAt);
    }

    @Test
    void defaultsMatchTheDdl() {
        var entity = new SqlReviewCustomRuleEntity();

        assertThat(entity.isEnabled()).isTrue();
        assertThat(entity.getDescription()).isNull();
        assertThat(entity.getVersion()).isZero();
        assertThat(entity.getCreatedAt()).isNotNull();
        assertThat(entity.getUpdatedAt()).isNotNull();
    }

    @Test
    void onUpdateRefreshesUpdatedAt() {
        var entity = new SqlReviewCustomRuleEntity();
        entity.setUpdatedAt(Instant.EPOCH);

        entity.onUpdate();

        assertThat(entity.getUpdatedAt()).isAfter(Instant.EPOCH);
    }
}
