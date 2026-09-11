package com.bablsoft.accessflow.sqlreview.internal.persistence.entity;

import com.bablsoft.accessflow.sqlreview.api.SqlReviewSeverity;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SqlReviewRuleConfigEntityTest {

    @Test
    void gettersReturnSetValues() {
        var entity = new SqlReviewRuleConfigEntity();
        var id = UUID.randomUUID();
        var ruleset = new SqlReviewRulesetEntity();
        ruleset.setId(UUID.randomUUID());

        entity.setId(id);
        entity.setRuleset(ruleset);
        entity.setRuleId("disallowed_function");
        entity.setSeverity(SqlReviewSeverity.BLOCK);
        entity.setParams("{\"names\":[\"pg_sleep\"]}");
        entity.setVersion(2L);

        assertThat(entity.getId()).isEqualTo(id);
        assertThat(entity.getRuleset()).isSameAs(ruleset);
        assertThat(entity.getRuleId()).isEqualTo("disallowed_function");
        assertThat(entity.getSeverity()).isEqualTo(SqlReviewSeverity.BLOCK);
        assertThat(entity.getParams()).isEqualTo("{\"names\":[\"pg_sleep\"]}");
        assertThat(entity.getVersion()).isEqualTo(2L);
    }

    @Test
    void defaultsMatchTheDdl() {
        var entity = new SqlReviewRuleConfigEntity();

        assertThat(entity.getParams()).isNull();
        assertThat(entity.getSeverity()).isNull();
        assertThat(entity.getVersion()).isZero();
    }
}
