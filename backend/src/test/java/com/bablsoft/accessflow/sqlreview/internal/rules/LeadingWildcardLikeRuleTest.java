package com.bablsoft.accessflow.sqlreview.internal.rules;

import com.bablsoft.accessflow.sqlreview.api.SqlReviewSeverity;
import com.bablsoft.accessflow.sqlreview.api.SqlRuleCategory;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.bablsoft.accessflow.sqlreview.internal.rules.RuleTestSupport.apply;
import static org.assertj.core.api.Assertions.assertThat;

class LeadingWildcardLikeRuleTest {

    private final LeadingWildcardLikeRule rule = new LeadingWildcardLikeRule();

    @Test
    void describesItself() {
        assertThat(rule.ruleId()).isEqualTo("leading_wildcard_like");
        assertThat(rule.category()).isEqualTo(SqlRuleCategory.PERFORMANCE);
        assertThat(rule.defaultSeverity()).isEqualTo(SqlReviewSeverity.WARN);
        assertThat(rule.messageArgKeys()).containsExactly("pattern");
    }

    @Test
    void firesOnLeadingPercentAnywhereInTheStatement() {
        var findings = apply(rule, "SELECT a FROM t\nWHERE name LIKE '%smith'");
        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).args()).isEqualTo(Map.of("pattern", "%smith"));
        assertThat(findings.get(0).lineNumber()).isEqualTo(2);
        assertThat(apply(rule, "SELECT a FROM t WHERE name ILIKE '%x'")).hasSize(1);
        assertThat(apply(rule, "SELECT a FROM t WHERE name NOT LIKE '%x'")).hasSize(1);
        assertThat(apply(rule, "SELECT a FROM t JOIN u ON u.name LIKE '%x'")).hasSize(1);
        assertThat(apply(rule, "SELECT a FROM t WHERE id IN (SELECT id FROM u WHERE k LIKE '%z')")).hasSize(1);
        assertThat(apply(rule, "UPDATE t SET a = 1 WHERE b LIKE '%x' OR c LIKE '%y'")).hasSize(2);
        assertThat(apply(rule, "DELETE FROM t WHERE b LIKE '%'")).hasSize(1);
    }

    @Test
    void doesNotFireOnTrailingWildcardsOrNonLiteralPatterns() {
        assertThat(apply(rule, "SELECT a FROM t WHERE name LIKE 'smith%'")).isEmpty();
        assertThat(apply(rule, "SELECT a FROM t WHERE name LIKE 'sm%th'")).isEmpty();
        assertThat(apply(rule, "SELECT a FROM t WHERE name LIKE ?")).isEmpty();
        assertThat(apply(rule, "SELECT a FROM t WHERE name LIKE other")).isEmpty();
        assertThat(apply(rule, "SELECT a FROM t WHERE name = '%x'")).isEmpty();
    }
}
