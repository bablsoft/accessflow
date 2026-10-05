package com.bablsoft.accessflow.sqlreview.internal.rules.condition;

import com.bablsoft.accessflow.sqlreview.api.SqlReviewSeverity;
import com.bablsoft.accessflow.sqlreview.api.SqlRuleCategory;
import com.bablsoft.accessflow.sqlreview.api.SqlRuleCondition;
import com.bablsoft.accessflow.sqlreview.internal.rules.SqlRuleContext;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CustomSqlRuleTest {

    private static SqlRuleContext context(int index, String sql) throws JSQLParserException {
        return new SqlRuleContext(index, CCJSqlParserUtil.parse(sql), false, true);
    }

    private static CustomSqlRule rule(String message, SqlRuleCondition condition) {
        return new CustomSqlRule("custom_billing_writes", "Billing writes", "No unbounded billing writes",
                SqlRuleCategory.DATA_PROTECTION, SqlReviewSeverity.BLOCK, message, condition);
    }

    @Test
    void describesItselfAsItsRow() {
        var rule = rule("m", new SqlRuleCondition.HasWhereClause(false));

        assertThat(rule.ruleId()).isEqualTo("custom_billing_writes");
        assertThat(rule.name()).isEqualTo("Billing writes");
        assertThat(rule.description()).isEqualTo("No unbounded billing writes");
        assertThat(rule.category()).isEqualTo(SqlRuleCategory.DATA_PROTECTION);
        assertThat(rule.defaultSeverity()).isEqualTo(SqlReviewSeverity.BLOCK);
        assertThat(rule.messageArgKeys()).containsExactly("message");
        assertThat(rule.params()).isEmpty();
    }

    @Test
    void recognisesCustomIds() {
        assertThat(CustomSqlRule.isCustomId("custom_x")).isTrue();
        assertThat(CustomSqlRule.isCustomId("select_star")).isFalse();
        assertThat(CustomSqlRule.isCustomId(null)).isFalse();
    }

    @Test
    void matchingStatementYieldsOneFindingWithPlaceholdersSubstituted() throws JSQLParserException {
        var rule = rule("{statement_type} on {tables} calling {functions} — {unknown}",
                new SqlRuleCondition.ReferencedTableMatches(List.of("billing.*")));

        var findings = rule.apply(context(2, "UPDATE billing.invoices SET total = round(total) "
                + "WHERE id IN (SELECT id FROM billing.lines WHERE abs(x) > 1)"), Map.of());

        assertThat(findings).singleElement().satisfies(finding -> {
            assertThat(finding.ruleId()).isEqualTo("custom_billing_writes");
            assertThat(finding.severity()).isEqualTo(SqlReviewSeverity.BLOCK);
            assertThat(finding.statementIndex()).isEqualTo(2);
            assertThat(finding.args()).containsExactly(Map.entry("message",
                    "UPDATE on billing.invoices, billing.lines calling abs, round — {unknown}"));
        });
    }

    @Test
    void nonMatchingStatementYieldsNothing() throws JSQLParserException {
        var rule = rule("m", new SqlRuleCondition.ReferencedTableMatches(List.of("billing.*")));

        assertThat(rule.apply(context(0, "SELECT * FROM sales.orders"), Map.of())).isEmpty();
    }

    @Test
    void elidesAnOverlongRenderedMessage() throws JSQLParserException {
        var rule = rule("x".repeat(CustomSqlRule.RENDERED_MAX_LENGTH) + "{tables}", new SqlRuleCondition.HasLimitClause(false));

        var message = rule.apply(context(0, "SELECT * FROM t"), Map.of()).get(0).args().get("message");

        assertThat(message).hasSize(CustomSqlRule.RENDERED_MAX_LENGTH).endsWith("…");
    }

    @Test
    void elisionNeverSplitsASurrogatePair() throws JSQLParserException {
        var prefix = "x".repeat(CustomSqlRule.RENDERED_MAX_LENGTH - 2);
        var rule = rule(prefix + "\uD83D\uDE00" + "y".repeat(10), new SqlRuleCondition.HasLimitClause(false));

        var message = rule.apply(context(0, "SELECT * FROM t"), Map.of()).get(0).args().get("message");

        assertThat(message).isEqualTo(prefix + "…");
    }

    @Test
    void requiresItsIdentityAndCondition() {
        assertThatThrownBy(() -> new CustomSqlRule(null, "n", null, SqlRuleCategory.PERFORMANCE,
                SqlReviewSeverity.WARN, "m", new SqlRuleCondition.HasOrderBy(true)))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new CustomSqlRule("custom_x", "n", null, SqlRuleCategory.PERFORMANCE,
                SqlReviewSeverity.WARN, "m", null)).isInstanceOf(NullPointerException.class);
    }
}
