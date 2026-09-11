package com.bablsoft.accessflow.sqlreview.internal.rules;

import com.bablsoft.accessflow.sqlreview.api.SqlReviewSeverity;
import com.bablsoft.accessflow.sqlreview.api.SqlRuleCategory;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.bablsoft.accessflow.sqlreview.internal.rules.RuleTestSupport.apply;
import static org.assertj.core.api.Assertions.assertThat;

class DdlStatementRuleTest {

    private final DdlStatementRule rule = new DdlStatementRule();

    @Test
    void describesItself() {
        assertThat(rule.ruleId()).isEqualTo("ddl_statement");
        assertThat(rule.category()).isEqualTo(SqlRuleCategory.SCHEMA_CHANGE);
        assertThat(rule.defaultSeverity()).isEqualTo(SqlReviewSeverity.WARN);
        assertThat(rule.messageArgKeys()).containsExactly("statement_type");
    }

    @Test
    void firesOnEveryDdlFamilyWithAnAnchor() {
        assertThat(apply(rule, "DROP TABLE t").get(0).args()).isEqualTo(Map.of("statement_type", "DROP"));
        assertThat(apply(rule, "TRUNCATE TABLE t").get(0).args()).isEqualTo(Map.of("statement_type", "TRUNCATE"));
        assertThat(apply(rule, "ALTER TABLE t ADD COLUMN c INT").get(0).args())
                .isEqualTo(Map.of("statement_type", "ALTER"));
        var create = apply(rule, "CREATE TABLE t (id INT)");
        assertThat(create.get(0).args()).isEqualTo(Map.of("statement_type", "CREATE TABLE"));
        assertThat(create.get(0).lineNumber()).isEqualTo(1);
        var index = apply(rule, "CREATE INDEX ix ON t (id)");
        assertThat(index).hasSize(1);
        assertThat(index.get(0).lineNumber()).isNull();
    }

    @Test
    void ignoresDmlAndSelect() {
        assertThat(apply(rule, "SELECT a FROM t")).isEmpty();
        assertThat(apply(rule, "INSERT INTO t VALUES (1)")).isEmpty();
        assertThat(apply(rule, "UPDATE t SET a = 1")).isEmpty();
        assertThat(apply(rule, "DELETE FROM t")).isEmpty();
    }
}
