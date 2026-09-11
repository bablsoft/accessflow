package com.bablsoft.accessflow.sqlreview.internal.rules;

import com.bablsoft.accessflow.sqlreview.api.SqlReviewSeverity;
import com.bablsoft.accessflow.sqlreview.api.SqlRuleCategory;
import org.junit.jupiter.api.Test;

import static com.bablsoft.accessflow.sqlreview.internal.rules.RuleTestSupport.apply;
import static org.assertj.core.api.Assertions.assertThat;

class OrderByWithoutLimitRuleTest {

    private final OrderByWithoutLimitRule rule = new OrderByWithoutLimitRule();

    @Test
    void describesItself() {
        assertThat(rule.ruleId()).isEqualTo("order_by_without_limit");
        assertThat(rule.category()).isEqualTo(SqlRuleCategory.PERFORMANCE);
        assertThat(rule.defaultSeverity()).isEqualTo(SqlReviewSeverity.WARN);
    }

    @Test
    void firesOnOrderedSelectWithoutLimit() {
        assertThat(apply(rule, "SELECT a FROM t ORDER BY a")).hasSize(1);
        assertThat(apply(rule, "SELECT a FROM t UNION SELECT b FROM u ORDER BY 1")).hasSize(1);
        assertThat(apply(rule, "(SELECT a FROM t) UNION (SELECT b FROM u) ORDER BY 1")).hasSize(1);
    }

    @Test
    void firesOnMysqlStyleOrderedDml() {
        assertThat(apply(rule, "UPDATE t SET a = 1 ORDER BY a")).hasSize(1);
        assertThat(apply(rule, "DELETE FROM t ORDER BY a")).hasSize(1);
        assertThat(apply(rule, "UPDATE t SET a = 1 ORDER BY a LIMIT 1")).isEmpty();
        assertThat(apply(rule, "DELETE FROM t ORDER BY a LIMIT 1")).isEmpty();
    }

    @Test
    void doesNotFireWhenBoundedOrUnordered() {
        assertThat(apply(rule, "SELECT a FROM t ORDER BY a LIMIT 10")).isEmpty();
        assertThat(apply(rule, "SELECT TOP 5 a FROM t ORDER BY a")).isEmpty();
        assertThat(apply(rule, "SELECT a FROM t ORDER BY a FETCH FIRST 5 ROWS ONLY")).isEmpty();
        assertThat(apply(rule, "SELECT a FROM t")).isEmpty();
        assertThat(apply(rule, "UPDATE t SET a = 1")).isEmpty();
        assertThat(apply(rule, "INSERT INTO t VALUES (1)")).isEmpty();
    }
}
