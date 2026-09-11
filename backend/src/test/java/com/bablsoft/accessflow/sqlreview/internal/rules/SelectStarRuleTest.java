package com.bablsoft.accessflow.sqlreview.internal.rules;

import com.bablsoft.accessflow.sqlreview.api.SqlReviewSeverity;
import com.bablsoft.accessflow.sqlreview.api.SqlRuleCategory;
import org.junit.jupiter.api.Test;

import static com.bablsoft.accessflow.sqlreview.internal.rules.RuleTestSupport.apply;
import static org.assertj.core.api.Assertions.assertThat;

class SelectStarRuleTest {

    private final SelectStarRule rule = new SelectStarRule();

    @Test
    void describesItself() {
        assertThat(rule.ruleId()).isEqualTo("select_star");
        assertThat(rule.category()).isEqualTo(SqlRuleCategory.PERFORMANCE);
        assertThat(rule.defaultSeverity()).isEqualTo(SqlReviewSeverity.WARN);
        assertThat(rule.params()).isEmpty();
        assertThat(rule.messageArgKeys()).isEmpty();
    }

    @Test
    void firesOnBareStarWithLineNumber() {
        var findings = apply(rule, "SELECT\n  *\nFROM t");
        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).ruleId()).isEqualTo("select_star");
        assertThat(findings.get(0).severity()).isEqualTo(SqlReviewSeverity.WARN);
        assertThat(findings.get(0).lineNumber()).isEqualTo(2);
        assertThat(findings.get(0).args()).isEmpty();
    }

    @Test
    void firesOnTableQualifiedStarAndEveryUnionBranchAndCteBody() {
        assertThat(apply(rule, "SELECT t.* FROM t")).hasSize(1);
        assertThat(apply(rule, "SELECT * FROM a UNION SELECT * FROM b")).hasSize(2);
        assertThat(apply(rule, "WITH c AS (SELECT * FROM x) SELECT id FROM c")).hasSize(1);
        assertThat(apply(rule, "(SELECT * FROM a) UNION (SELECT b FROM c)")).hasSize(1);
    }

    @Test
    void ignoresExplicitColumnsCountStarAndSubqueries() {
        assertThat(apply(rule, "SELECT id, name FROM t")).isEmpty();
        assertThat(apply(rule, "SELECT count(*) FROM t")).isEmpty();
        assertThat(apply(rule, "SELECT id FROM t WHERE EXISTS (SELECT * FROM u WHERE u.id = t.id)")).isEmpty();
        assertThat(apply(rule, "SELECT id FROM (SELECT * FROM u) x")).isEmpty();
        assertThat(apply(rule, "UPDATE t SET a = 1 WHERE id = 1")).isEmpty();
    }
}
