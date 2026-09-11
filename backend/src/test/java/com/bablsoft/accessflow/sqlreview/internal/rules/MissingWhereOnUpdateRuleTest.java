package com.bablsoft.accessflow.sqlreview.internal.rules;

import com.bablsoft.accessflow.sqlreview.api.SqlReviewSeverity;
import com.bablsoft.accessflow.sqlreview.api.SqlRuleCategory;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.bablsoft.accessflow.sqlreview.internal.rules.RuleTestSupport.apply;
import static org.assertj.core.api.Assertions.assertThat;

class MissingWhereOnUpdateRuleTest {

    private final MissingWhereOnUpdateRule rule = new MissingWhereOnUpdateRule();

    @Test
    void describesItself() {
        assertThat(rule.ruleId()).isEqualTo("missing_where_on_update");
        assertThat(rule.category()).isEqualTo(SqlRuleCategory.STATEMENT_SAFETY);
        assertThat(rule.defaultSeverity()).isEqualTo(SqlReviewSeverity.BLOCK);
        assertThat(rule.messageArgKeys()).containsExactly("table");
    }

    @Test
    void firesOnUpdateWithoutWhere() {
        var findings = apply(rule, "UPDATE \"Payroll\".Salaries SET amount = 0");
        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).args()).isEqualTo(Map.of("table", "payroll.salaries"));
        assertThat(findings.get(0).lineNumber()).isEqualTo(1);
        assertThat(findings.get(0).severity()).isEqualTo(SqlReviewSeverity.BLOCK);
    }

    @Test
    void doesNotFireWithAnyWhereEvenATautology() {
        assertThat(apply(rule, "UPDATE t SET a = 1 WHERE id = 5")).isEmpty();
        // The anti-defeat case belongs to where_always_true, not to this rule.
        assertThat(apply(rule, "UPDATE t SET a = 1 WHERE 1 = 1")).isEmpty();
    }

    @Test
    void ignoresOtherStatements() {
        assertThat(apply(rule, "DELETE FROM t")).isEmpty();
        assertThat(apply(rule, "SELECT a FROM t")).isEmpty();
    }
}
