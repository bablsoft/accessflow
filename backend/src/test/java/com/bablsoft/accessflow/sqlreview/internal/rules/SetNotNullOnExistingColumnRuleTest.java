package com.bablsoft.accessflow.sqlreview.internal.rules;

import com.bablsoft.accessflow.sqlreview.api.SqlReviewSeverity;
import com.bablsoft.accessflow.sqlreview.api.SqlRuleCategory;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.bablsoft.accessflow.sqlreview.internal.rules.RuleTestSupport.apply;
import static org.assertj.core.api.Assertions.assertThat;

class SetNotNullOnExistingColumnRuleTest {

    private final SetNotNullOnExistingColumnRule rule = new SetNotNullOnExistingColumnRule();

    @Test
    void describesItself() {
        assertThat(rule.ruleId()).isEqualTo("set_not_null_on_existing_column");
        assertThat(rule.category()).isEqualTo(SqlRuleCategory.SCHEMA_CHANGE);
        assertThat(rule.defaultSeverity()).isEqualTo(SqlReviewSeverity.WARN);
        assertThat(rule.messageArgKeys()).containsExactly("column");
    }

    @Test
    void firesOnSetNotNull() {
        var findings = apply(rule, "ALTER TABLE \"Sales\".Orders ALTER COLUMN Note SET NOT NULL");
        assertThat(findings).singleElement().satisfies(f -> {
            assertThat(f.args()).isEqualTo(Map.of("column", "sales.orders.note"));
            assertThat(f.lineNumber()).isEqualTo(1);
        });
    }

    @Test
    void firesOnOracleTypelessModify() {
        assertThat(apply(rule, "ALTER TABLE t MODIFY c NOT NULL")).singleElement()
                .satisfies(f -> assertThat(f.args()).containsEntry("column", "t.c"));
    }

    @Test
    void firesOncePerColumnAcrossActions() {
        var findings = apply(rule,
                "ALTER TABLE t ADD COLUMN a INT, ALTER COLUMN b SET NOT NULL, ALTER COLUMN c SET NOT NULL");
        assertThat(findings).extracting(f -> f.args().get("column")).containsExactly("t.b", "t.c");
    }

    @Test
    void ignoresOtherAlters() {
        assertThat(apply(rule, "ALTER TABLE t ALTER COLUMN c DROP NOT NULL")).isEmpty();
        assertThat(apply(rule, "ALTER TABLE t MODIFY c VARCHAR(20) NOT NULL")).isEmpty();
        assertThat(apply(rule, "ALTER TABLE t MODIFY c VARCHAR(20)")).isEmpty();
        assertThat(apply(rule, "ALTER TABLE t ADD COLUMN c INT NOT NULL")).isEmpty();
        assertThat(apply(rule, "ALTER TABLE t ALTER COLUMN c TYPE INT")).isEmpty();
        assertThat(apply(rule, "DROP TABLE t")).isEmpty();
    }
}
