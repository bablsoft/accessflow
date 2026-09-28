package com.bablsoft.accessflow.sqlreview.internal.rules;

import com.bablsoft.accessflow.sqlreview.api.SqlReviewSeverity;
import com.bablsoft.accessflow.sqlreview.api.SqlRuleCategory;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.bablsoft.accessflow.sqlreview.internal.rules.RuleTestSupport.apply;
import static org.assertj.core.api.Assertions.assertThat;

class AlterColumnTypeRuleTest {

    private final AlterColumnTypeRule rule = new AlterColumnTypeRule();

    @Test
    void describesItself() {
        assertThat(rule.ruleId()).isEqualTo("alter_column_type");
        assertThat(rule.category()).isEqualTo(SqlRuleCategory.SCHEMA_CHANGE);
        assertThat(rule.defaultSeverity()).isEqualTo(SqlReviewSeverity.WARN);
        assertThat(rule.messageArgKeys()).containsExactly("column", "type");
    }

    @Test
    void firesOnPostgresqlAlterColumnType() {
        var findings = apply(rule, "ALTER TABLE orders ALTER COLUMN note TYPE VARCHAR(20)");
        assertThat(findings).singleElement().satisfies(f -> {
            assertThat(f.args()).isEqualTo(Map.of("column", "orders.note", "type", "VARCHAR (20)"));
            assertThat(f.lineNumber()).isEqualTo(1);
        });
        assertThat(apply(rule, "ALTER TABLE t ALTER COLUMN c TYPE INT USING c::int")).hasSize(1);
    }

    @Test
    void firesOnTheOtherDialectsRedefinitions() {
        assertThat(apply(rule, "ALTER TABLE t ALTER COLUMN c VARCHAR(20) NOT NULL")).singleElement()
                .satisfies(f -> assertThat(f.args()).containsEntry("column", "t.c"));
        assertThat(apply(rule, "ALTER TABLE t MODIFY c VARCHAR(20) NOT NULL")).hasSize(1);
        assertThat(apply(rule, "ALTER TABLE t MODIFY (c NUMBER(5) NOT NULL)")).singleElement()
                .satisfies(f -> assertThat(f.args()).containsEntry("type", "NUMBER (5)"));
    }

    @Test
    void reportsTheExistingColumnOfAChange() {
        assertThat(apply(rule, "ALTER TABLE t CHANGE c d VARCHAR(20)")).singleElement()
                .satisfies(f -> assertThat(f.args()).containsEntry("column", "t.c"));
    }

    @Test
    void firesOncePerColumn() {
        assertThat(apply(rule, "ALTER TABLE t ALTER COLUMN a TYPE BIGINT, ALTER COLUMN b TYPE TEXT"))
                .extracting(f -> f.args().get("column")).containsExactly("t.a", "t.b");
    }

    @Test
    void ignoresNonTypeAlters() {
        assertThat(apply(rule, "ALTER TABLE t ALTER COLUMN c SET NOT NULL")).isEmpty();
        assertThat(apply(rule, "ALTER TABLE t ALTER COLUMN c DROP NOT NULL")).isEmpty();
        assertThat(apply(rule, "ALTER TABLE t ALTER COLUMN c SET DEFAULT 0")).isEmpty();
        assertThat(apply(rule, "ALTER TABLE t MODIFY c NOT NULL")).isEmpty();
        assertThat(apply(rule, "ALTER TABLE t ADD COLUMN c INT")).isEmpty();
        assertThat(apply(rule, "SELECT a FROM t")).isEmpty();
    }
}
