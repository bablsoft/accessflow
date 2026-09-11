package com.bablsoft.accessflow.sqlreview.internal.rules;

import com.bablsoft.accessflow.sqlreview.api.SqlReviewSeverity;
import com.bablsoft.accessflow.sqlreview.api.SqlRuleCategory;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.bablsoft.accessflow.sqlreview.internal.rules.RuleTestSupport.apply;
import static org.assertj.core.api.Assertions.assertThat;

class TruncateStatementRuleTest {

    private final TruncateStatementRule rule = new TruncateStatementRule();

    @Test
    void describesItself() {
        assertThat(rule.ruleId()).isEqualTo("truncate_statement");
        assertThat(rule.category()).isEqualTo(SqlRuleCategory.SCHEMA_CHANGE);
        assertThat(rule.defaultSeverity()).isEqualTo(SqlReviewSeverity.BLOCK);
        assertThat(rule.messageArgKeys()).containsExactly("table");
    }

    @Test
    void firesOncePerTruncatedTable() {
        var single = apply(rule, "TRUNCATE TABLE `audit`.log");
        assertThat(single).hasSize(1);
        assertThat(single.get(0).args()).isEqualTo(Map.of("table", "audit.log"));
        assertThat(single.get(0).lineNumber()).isEqualTo(1);
        var multi = apply(rule, "TRUNCATE a, b");
        assertThat(multi).extracting(f -> f.args().get("table")).containsExactly("a", "b");
    }

    @Test
    void ignoresOtherStatements() {
        assertThat(apply(rule, "DELETE FROM t")).isEmpty();
        assertThat(apply(rule, "DROP TABLE t")).isEmpty();
    }
}
