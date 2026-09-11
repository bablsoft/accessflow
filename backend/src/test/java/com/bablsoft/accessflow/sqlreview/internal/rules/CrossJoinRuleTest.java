package com.bablsoft.accessflow.sqlreview.internal.rules;

import com.bablsoft.accessflow.sqlreview.api.SqlReviewSeverity;
import com.bablsoft.accessflow.sqlreview.api.SqlRuleCategory;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.bablsoft.accessflow.sqlreview.internal.rules.RuleTestSupport.apply;
import static org.assertj.core.api.Assertions.assertThat;

class CrossJoinRuleTest {

    private final CrossJoinRule rule = new CrossJoinRule();

    @Test
    void describesItself() {
        assertThat(rule.ruleId()).isEqualTo("cross_join");
        assertThat(rule.category()).isEqualTo(SqlRuleCategory.PERFORMANCE);
        assertThat(rule.defaultSeverity()).isEqualTo(SqlReviewSeverity.WARN);
        assertThat(rule.messageArgKeys()).containsExactly("table");
    }

    @Test
    void firesOnExplicitCrossJoinAndJoinWithoutCondition() {
        var findings = apply(rule, "SELECT a FROM t\nCROSS JOIN u");
        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).args()).isEqualTo(Map.of("table", "u"));
        assertThat(findings.get(0).lineNumber()).isEqualTo(2);
        assertThat(apply(rule, "SELECT a FROM t JOIN u")).hasSize(1);
        assertThat(apply(rule, "SELECT a FROM t INNER JOIN u")).hasSize(1);
    }

    @Test
    void firesOnCommaJoinWithoutCorrelation() {
        assertThat(apply(rule, "SELECT a FROM t, u")).hasSize(1);
        assertThat(apply(rule, "SELECT a FROM t, u WHERE t.id = 5")).hasSize(1);
        assertThat(apply(rule, "SELECT a FROM t, u WHERE t.id = t.parent_id")).hasSize(1);
        assertThat(apply(rule, "SELECT a FROM t, u, v WHERE t.id = 5")).hasSize(2);
        // Correlation is judged per join: t–u are correlated, v is still a product.
        var threeWay = apply(rule, "SELECT a FROM t, u, v WHERE t.id = u.id");
        assertThat(threeWay).hasSize(1);
        assertThat(threeWay.get(0).args()).isEqualTo(Map.of("table", "v"));
        // Known limitation: an arithmetic side is not recognised as a correlation.
        assertThat(apply(rule, "SELECT a FROM t, u WHERE t.id = u.id + 1")).hasSize(1);
    }

    @Test
    void doesNotFireOnCorrelatedOrConditionedJoins() {
        assertThat(apply(rule, "SELECT a FROM t JOIN u ON t.id = u.id")).isEmpty();
        assertThat(apply(rule, "SELECT a FROM t LEFT JOIN u ON t.id = u.id")).isEmpty();
        assertThat(apply(rule, "SELECT a FROM t JOIN u USING (id)")).isEmpty();
        assertThat(apply(rule, "SELECT a FROM t NATURAL JOIN u")).isEmpty();
        assertThat(apply(rule, "SELECT a FROM t t1, u WHERE t1.id = u.id")).isEmpty();
        assertThat(apply(rule, "SELECT a FROM t, u WHERE t.id = u.id AND t.x = 1")).isEmpty();
        assertThat(apply(rule, "SELECT a FROM t, u WHERE (t.id = u.id)")).isEmpty();
        assertThat(apply(rule, "SELECT a FROM t, u WHERE t.id > u.id")).isEmpty();
        // Unqualified columns get the benefit of the doubt.
        assertThat(apply(rule, "SELECT a FROM t, u WHERE id = uid")).isEmpty();
        assertThat(apply(rule, "SELECT a FROM t, u WHERE t.id = uid")).isEmpty();
        assertThat(apply(rule, "SELECT a FROM t OUTER APPLY fn(t.id) f")).isEmpty();
        assertThat(apply(rule, "SELECT a FROM t CROSS APPLY fn(t.id) f")).isEmpty();
        assertThat(apply(rule, "SELECT a FROM t t1, u WHERE u.id = t1.id")).isEmpty();
        assertThat(apply(rule, "SELECT a FROM public.t, u WHERE public.t.id = u.id")).isEmpty();
        assertThat(apply(rule, "SELECT a FROM t")).isEmpty();
        assertThat(apply(rule, "DELETE FROM t")).isEmpty();
    }

    @Test
    void inspectsSubqueriesAndDescribesNonTableJoins() {
        assertThat(apply(rule, "SELECT a FROM t WHERE id IN (SELECT id FROM u CROSS JOIN v)")).hasSize(1);
        var derived = apply(rule, "SELECT a FROM t CROSS JOIN (SELECT 1 AS x) sub");
        assertThat(derived).hasSize(1);
        assertThat(derived.get(0).args()).isEqualTo(Map.of("table", "sub"));
    }

    @Test
    void columnPairsHandleNullNonComparisonsAndNesting() {
        assertThat(CrossJoinRule.columnPairs(null)).isEmpty();
        assertThat(CrossJoinRule.columnPairs(RuleTestSupportExpressions.expression("t.id = 5"))).isEmpty();
        assertThat(CrossJoinRule.columnPairs(RuleTestSupportExpressions.expression("NOT t.id = u.id"))).isEmpty();
        assertThat(CrossJoinRule.columnPairs(RuleTestSupportExpressions.expression("(t.id = u.id OR a = b) AND t.x = t.y")))
                .containsExactly(new CrossJoinRule.ColumnPair("t", "u"), new CrossJoinRule.ColumnPair(null, null),
                        new CrossJoinRule.ColumnPair("t", "t"));
    }
}
