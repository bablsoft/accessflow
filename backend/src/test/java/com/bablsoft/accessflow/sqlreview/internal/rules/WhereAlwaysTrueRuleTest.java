package com.bablsoft.accessflow.sqlreview.internal.rules;

import com.bablsoft.accessflow.sqlreview.api.SqlReviewSeverity;
import com.bablsoft.accessflow.sqlreview.api.SqlRuleCategory;
import org.junit.jupiter.api.Test;

import static com.bablsoft.accessflow.sqlreview.internal.rules.RuleTestSupport.apply;
import static org.assertj.core.api.Assertions.assertThat;

class WhereAlwaysTrueRuleTest {

    private final WhereAlwaysTrueRule rule = new WhereAlwaysTrueRule();

    @Test
    void describesItself() {
        assertThat(rule.ruleId()).isEqualTo("where_always_true");
        assertThat(rule.category()).isEqualTo(SqlRuleCategory.STATEMENT_SAFETY);
        assertThat(rule.defaultSeverity()).isEqualTo(SqlReviewSeverity.BLOCK);
        assertThat(rule.messageArgKeys()).containsExactly("predicate");
    }

    @Test
    void catchesTheUpdateThatMissingWhereOnUpdateLetsThrough() {
        // The anti-defeat pair: missing_where_on_update is silent on this statement.
        assertThat(apply(new MissingWhereOnUpdateRule(), "UPDATE t SET a = 1 WHERE 1 = 1")).isEmpty();
        var findings = apply(rule, "UPDATE t SET a = 1\nWHERE 1 = 1");
        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).args()).containsEntry("predicate", "1 = 1");
        assertThat(findings.get(0).lineNumber()).isEqualTo(2);
    }

    @Test
    void firesOnEveryTautologyShape() {
        assertThat(apply(rule, "DELETE FROM t WHERE TRUE")).hasSize(1);
        assertThat(apply(rule, "DELETE FROM t WHERE (1 = 1)")).hasSize(1);
        assertThat(apply(rule, "DELETE FROM t WHERE ((1 = 1))")).hasSize(1);
        assertThat(apply(rule, "DELETE FROM t WHERE x = x")).hasSize(1);
        assertThat(apply(rule, "DELETE FROM t WHERE t.x = t.x")).hasSize(1);
        assertThat(apply(rule, "DELETE FROM t WHERE 'a' = 'a'")).hasSize(1);
        assertThat(apply(rule, "SELECT a FROM t WHERE 1 = 1")).hasSize(1);
        assertThat(apply(rule, "SELECT a FROM t WHERE 1 = 1 UNION SELECT b FROM u WHERE b = 2")).hasSize(1);
    }

    @Test
    void firesOnTopLevelOrDisjunct() {
        assertThat(apply(rule, "DELETE FROM t WHERE id = 1 OR 1 = 1")).hasSize(1);
        assertThat(apply(rule, "DELETE FROM t WHERE id = 1 OR (1 = 1)")).hasSize(1);
        assertThat(apply(rule, "DELETE FROM t WHERE id = 1 OR b = 2 OR TRUE")).hasSize(1);
    }

    @Test
    void doesNotFireOnRealPredicates() {
        assertThat(apply(rule, "DELETE FROM t WHERE a = b")).isEmpty();
        assertThat(apply(rule, "DELETE FROM t WHERE t.x = x")).isEmpty();
        assertThat(apply(rule, "DELETE FROM t WHERE 1 = 2")).isEmpty();
        assertThat(apply(rule, "DELETE FROM t WHERE FALSE")).isEmpty();
        assertThat(apply(rule, "DELETE FROM t WHERE id = 1 AND 1 = 1")).isEmpty();
        assertThat(apply(rule, "DELETE FROM t WHERE (id = 1 OR 1 = 1) AND b = 2")).isEmpty();
        assertThat(apply(rule, "DELETE FROM t")).isEmpty();
        assertThat(apply(rule, "SELECT a FROM t")).isEmpty();
        assertThat(apply(rule, "INSERT INTO t VALUES (1)")).isEmpty();
    }
}
