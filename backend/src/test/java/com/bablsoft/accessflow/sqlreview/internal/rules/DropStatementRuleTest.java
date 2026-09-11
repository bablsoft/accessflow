package com.bablsoft.accessflow.sqlreview.internal.rules;

import com.bablsoft.accessflow.sqlreview.api.SqlReviewSeverity;
import com.bablsoft.accessflow.sqlreview.api.SqlRuleCategory;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.bablsoft.accessflow.sqlreview.internal.rules.RuleTestSupport.apply;
import static org.assertj.core.api.Assertions.assertThat;

class DropStatementRuleTest {

    private final DropStatementRule rule = new DropStatementRule();

    @Test
    void describesItself() {
        assertThat(rule.ruleId()).isEqualTo("drop_statement");
        assertThat(rule.category()).isEqualTo(SqlRuleCategory.SCHEMA_CHANGE);
        assertThat(rule.defaultSeverity()).isEqualTo(SqlReviewSeverity.BLOCK);
        assertThat(rule.messageArgKeys()).containsExactly("object_type", "name");
    }

    @Test
    void firesOnDropTableAndSchema() {
        var table = apply(rule, "DROP TABLE IF EXISTS \"HR\".Employees");
        assertThat(table).hasSize(1);
        assertThat(table.get(0).args()).isEqualTo(Map.of("object_type", "TABLE", "name", "hr.employees"));
        assertThat(table.get(0).lineNumber()).isEqualTo(1);
        var schema = apply(rule, "DROP SCHEMA hr CASCADE");
        assertThat(schema.get(0).args()).isEqualTo(Map.of("object_type", "SCHEMA", "name", "hr"));
    }

    @Test
    void firesOncePerDroppedColumn() {
        var findings = apply(rule, "ALTER TABLE t DROP COLUMN c, DROP COLUMN d");
        assertThat(findings).hasSize(2);
        assertThat(findings.get(0).args()).isEqualTo(Map.of("object_type", "COLUMN", "name", "t.c"));
        assertThat(findings.get(1).args()).isEqualTo(Map.of("object_type", "COLUMN", "name", "t.d"));
    }

    @Test
    void ignoresOtherDropsAndAlters() {
        assertThat(apply(rule, "DROP INDEX ix")).isEmpty();
        assertThat(apply(rule, "DROP VIEW v")).isEmpty();
        assertThat(apply(rule, "ALTER TABLE t ADD COLUMN c INT")).isEmpty();
        assertThat(apply(rule, "ALTER TABLE t DROP PRIMARY KEY")).isEmpty();
        assertThat(apply(rule, "TRUNCATE TABLE t")).isEmpty();
        assertThat(apply(rule, "SELECT a FROM t")).isEmpty();
    }
}
