package com.bablsoft.accessflow.sqlreview.internal.rules;

import com.bablsoft.accessflow.sqlreview.api.SqlReviewSeverity;
import com.bablsoft.accessflow.sqlreview.api.SqlRuleCategory;
import org.junit.jupiter.api.Test;

import static com.bablsoft.accessflow.sqlreview.internal.rules.RuleTestSupport.apply;
import static org.assertj.core.api.Assertions.assertThat;

class MissingLimitOnSelectRuleTest {

    private final MissingLimitOnSelectRule rule = new MissingLimitOnSelectRule();

    @Test
    void describesItself() {
        assertThat(rule.ruleId()).isEqualTo("missing_limit_on_select");
        assertThat(rule.category()).isEqualTo(SqlRuleCategory.PERFORMANCE);
        assertThat(rule.defaultSeverity()).isEqualTo(SqlReviewSeverity.WARN);
        assertThat(rule.messageArgKeys()).isEmpty();
    }

    @Test
    void firesOnUnboundedSelect() {
        var findings = apply(rule, "SELECT a\nFROM t");
        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).lineNumber()).isEqualTo(1);
        assertThat(apply(rule, "SELECT a FROM t LIMIT ALL")).hasSize(1);
        assertThat(apply(rule, "SELECT a FROM t OFFSET 10")).hasSize(1);
        assertThat(apply(rule, "SELECT a FROM t UNION SELECT b FROM u")).hasSize(1);
    }

    @Test
    void acceptsEveryLimitDialect() {
        assertThat(apply(rule, "SELECT a FROM t LIMIT 10")).isEmpty();
        assertThat(apply(rule, "SELECT TOP 10 a FROM t")).isEmpty();
        assertThat(apply(rule, "SELECT a FROM t FETCH FIRST 10 ROWS ONLY")).isEmpty();
        assertThat(apply(rule, "SELECT a FROM t UNION SELECT b FROM u LIMIT 5")).isEmpty();
        assertThat(apply(rule, "(SELECT a FROM t) UNION (SELECT b FROM u) LIMIT 5")).isEmpty();
    }

    @Test
    void skipsTablelessSelectsAndOtherStatements() {
        assertThat(apply(rule, "SELECT 1")).isEmpty();
        assertThat(apply(rule, "SELECT now()")).isEmpty();
        assertThat(apply(rule, "DELETE FROM t")).isEmpty();
    }
}
