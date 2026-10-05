package com.bablsoft.accessflow.sqlreview.internal.rules.condition;

import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.sqlreview.api.SqlRuleCondition;
import com.bablsoft.accessflow.sqlreview.internal.rules.SqlRuleContext;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SqlRuleConditionEvaluatorTest {

    private static boolean matches(SqlRuleCondition condition, String sql) {
        return matches(condition, sql, false);
    }

    private static boolean matches(SqlRuleCondition condition, String sql, boolean transactional) {
        try {
            var context = new SqlRuleContext(0, CCJSqlParserUtil.parse(sql), transactional, !transactional);
            return new SqlRuleConditionEvaluator(condition).matches(context.facts());
        } catch (JSQLParserException ex) {
            throw new IllegalArgumentException(sql, ex);
        }
    }

    private static final SqlRuleCondition SELECT = new SqlRuleCondition.QueryTypeIn(Set.of(QueryType.SELECT));
    private static final SqlRuleCondition BILLING = new SqlRuleCondition.ReferencedTableMatches(List.of("billing.*"));

    @Test
    void combinators() {
        assertThat(matches(new SqlRuleCondition.And(List.of(SELECT, BILLING)), "SELECT * FROM billing.x")).isTrue();
        assertThat(matches(new SqlRuleCondition.And(List.of(SELECT, BILLING)), "SELECT * FROM sales.x")).isFalse();
        assertThat(matches(new SqlRuleCondition.Or(List.of(SELECT, BILLING)), "DELETE FROM billing.x")).isTrue();
        assertThat(matches(new SqlRuleCondition.Or(List.of(SELECT, BILLING)), "DELETE FROM sales.x")).isFalse();
        assertThat(matches(new SqlRuleCondition.Not(SELECT), "DELETE FROM t")).isTrue();
        assertThat(matches(new SqlRuleCondition.Not(SELECT), "SELECT 1")).isFalse();
    }

    @Test
    void queryType() {
        var ddl = new SqlRuleCondition.QueryTypeIn(Set.of(QueryType.DDL, QueryType.UPDATE));
        assertThat(matches(ddl, "DROP TABLE t")).isTrue();
        assertThat(matches(ddl, "UPDATE t SET a = 1")).isTrue();
        assertThat(matches(ddl, "SELECT 1")).isFalse();
    }

    @Test
    void referencedTableMatchesQualifiedAndBareNames() {
        assertThat(matches(BILLING, "SELECT * FROM Billing.Invoices")).isTrue();
        assertThat(matches(BILLING, "SELECT * FROM invoices")).isFalse();
        var bare = new SqlRuleCondition.ReferencedTableMatches(List.of("audit_log"));
        assertThat(matches(bare, "DELETE FROM public.audit_log")).isTrue();
        assertThat(matches(bare, "DELETE FROM public.audit_logs")).isFalse();
    }

    @Test
    void referencedColumnMatchesQualifiedAndBareNames() {
        var ssn = new SqlRuleCondition.ReferencedColumnMatches(List.of("ssn"));
        assertThat(matches(ssn, "SELECT u.ssn FROM users u")).isTrue();
        assertThat(matches(ssn, "UPDATE users SET ssn = NULL")).isTrue();
        assertThat(matches(ssn, "SELECT u.name FROM users u")).isFalse();
        var qualified = new SqlRuleCondition.ReferencedColumnMatches(List.of("u.*"));
        assertThat(matches(qualified, "SELECT u.name FROM users u")).isTrue();
        assertThat(matches(qualified, "SELECT name FROM users")).isFalse();
    }

    @Test
    void functionCalledIsUnqualifiedAndCaseInsensitive() {
        var dblink = new SqlRuleCondition.FunctionCalled(List.of("Public.DBLINK"));
        assertThat(matches(dblink, "SELECT * FROM t WHERE x = dblink('a', 'b')")).isTrue();
        assertThat(matches(dblink, "SELECT now()")).isFalse();
    }

    @Test
    void hasWhere() {
        assertThat(matches(new SqlRuleCondition.HasWhereClause(false), "DELETE FROM t")).isTrue();
        assertThat(matches(new SqlRuleCondition.HasWhereClause(false), "DELETE FROM t WHERE id = 1")).isFalse();
        assertThat(matches(new SqlRuleCondition.HasWhereClause(true), "DELETE FROM t WHERE id = 1")).isTrue();
    }

    @Test
    void hasLimit() {
        assertThat(matches(new SqlRuleCondition.HasLimitClause(false), "SELECT * FROM t")).isTrue();
        assertThat(matches(new SqlRuleCondition.HasLimitClause(false), "SELECT * FROM t LIMIT 1")).isFalse();
        assertThat(matches(new SqlRuleCondition.HasLimitClause(true), "SELECT TOP 5 * FROM t")).isTrue();
    }

    @Test
    void hasOrderBy() {
        assertThat(matches(new SqlRuleCondition.HasOrderBy(true), "SELECT * FROM t ORDER BY a")).isTrue();
        assertThat(matches(new SqlRuleCondition.HasOrderBy(true), "SELECT * FROM t")).isFalse();
    }

    @Test
    void whereAlwaysTrue() {
        assertThat(matches(new SqlRuleCondition.WhereAlwaysTrue(true), "UPDATE t SET a = 1 WHERE 1 = 1")).isTrue();
        assertThat(matches(new SqlRuleCondition.WhereAlwaysTrue(true), "UPDATE t SET a = 1 WHERE id = 1")).isFalse();
    }

    @Test
    void joinWithoutCondition() {
        assertThat(matches(new SqlRuleCondition.JoinWithoutCondition(true), "SELECT * FROM a, b")).isTrue();
        assertThat(matches(new SqlRuleCondition.JoinWithoutCondition(true), "SELECT * FROM a JOIN b ON a.id = b.id"))
                .isFalse();
    }

    @Test
    void likeLeadingWildcard() {
        assertThat(matches(new SqlRuleCondition.LikeLeadingWildcard(true), "SELECT * FROM t WHERE n LIKE '%a'"))
                .isTrue();
        assertThat(matches(new SqlRuleCondition.LikeLeadingWildcard(true), "SELECT * FROM t WHERE n LIKE 'a%'"))
                .isFalse();
    }

    @Test
    void transactional() {
        assertThat(matches(new SqlRuleCondition.Transactional(false), "DELETE FROM t WHERE id = 1")).isTrue();
        assertThat(matches(new SqlRuleCondition.Transactional(false), "DELETE FROM t WHERE id = 1", true)).isFalse();
        assertThat(matches(new SqlRuleCondition.Transactional(true), "DELETE FROM t WHERE id = 1", true)).isTrue();
    }

    @Test
    void sqlMatchesTheNormalisedText() {
        var sensitive = new SqlRuleCondition.SqlMatches("FROM Orders WHERE", false);
        assertThat(matches(sensitive, "SELECT a\n  FROM   Orders\n WHERE a = 1 -- note")).isTrue();
        assertThat(matches(sensitive, "select a from orders where a = 1")).isFalse();
        var insensitive = new SqlRuleCondition.SqlMatches("from orders where", true);
        assertThat(matches(insensitive, "SELECT a FROM Orders WHERE a = 1")).isTrue();
        assertThat(matches(new SqlRuleCondition.SqlMatches("secret", false), "SELECT a FROM t /* secret */")).isFalse();
    }

    @Test
    void runawayRegexThrowsSoTheEvaluatorSkipsTheRule() {
        var runaway = new SqlRuleCondition.SqlMatches("((a+)+)+b", false);
        assertThatThrownBy(() -> matches(runaway, "SELECT '" + "a".repeat(60) + "' FROM t"))
                .isInstanceOf(SafeRegex.RegexBudgetExceededException.class);
    }
}
