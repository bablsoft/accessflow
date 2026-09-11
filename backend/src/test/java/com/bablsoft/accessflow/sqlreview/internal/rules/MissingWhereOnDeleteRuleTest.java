package com.bablsoft.accessflow.sqlreview.internal.rules;

import com.bablsoft.accessflow.sqlreview.api.SqlReviewSeverity;
import com.bablsoft.accessflow.sqlreview.api.SqlRuleCategory;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.bablsoft.accessflow.sqlreview.internal.rules.RuleTestSupport.apply;
import static org.assertj.core.api.Assertions.assertThat;

class MissingWhereOnDeleteRuleTest {

    private final MissingWhereOnDeleteRule rule = new MissingWhereOnDeleteRule();

    @Test
    void describesItself() {
        assertThat(rule.ruleId()).isEqualTo("missing_where_on_delete");
        assertThat(rule.category()).isEqualTo(SqlRuleCategory.STATEMENT_SAFETY);
        assertThat(rule.defaultSeverity()).isEqualTo(SqlReviewSeverity.BLOCK);
        assertThat(rule.messageArgKeys()).containsExactly("table");
    }

    @Test
    void firesOnDeleteWithoutWhere() {
        var findings = apply(rule, "DELETE FROM\n  orders");
        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).args()).isEqualTo(Map.of("table", "orders"));
        assertThat(findings.get(0).lineNumber()).isEqualTo(2);
    }

    @Test
    void doesNotFireWithAnyWhereEvenATautology() {
        assertThat(apply(rule, "DELETE FROM t WHERE id = 5")).isEmpty();
        assertThat(apply(rule, "DELETE FROM t WHERE 1 = 1")).isEmpty();
    }

    @Test
    void ignoresOtherStatements() {
        assertThat(apply(rule, "UPDATE t SET a = 1")).isEmpty();
        assertThat(apply(rule, "TRUNCATE TABLE t")).isEmpty();
    }
}
