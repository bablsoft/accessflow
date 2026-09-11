package com.bablsoft.accessflow.sqlreview.internal;

import com.bablsoft.accessflow.core.api.SqlParseResult;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewFinding;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewSeverity;
import com.bablsoft.accessflow.sqlreview.api.SqlRuleCategory;
import com.bablsoft.accessflow.sqlreview.internal.rules.DmlWithoutTransactionRule;
import com.bablsoft.accessflow.sqlreview.internal.rules.LeadingWildcardLikeRule;
import com.bablsoft.accessflow.sqlreview.internal.rules.MissingWhereOnDeleteRule;
import com.bablsoft.accessflow.sqlreview.internal.rules.SelectStarRule;
import com.bablsoft.accessflow.sqlreview.internal.rules.SqlRule;
import com.bablsoft.accessflow.sqlreview.internal.rules.SqlRuleContext;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SqlReviewEvaluatorTest {

    private final SqlReviewEvaluator evaluator = new SqlReviewEvaluator();

    private static SqlRuleContext statement(int index, String sql) throws JSQLParserException {
        return new SqlRuleContext(index, CCJSqlParserUtil.parse(sql), false, true);
    }

    @Test
    void ordersByStatementThenLineNullsLastThenRuleId() throws JSQLParserException {
        var second = statement(1, "SELECT *\nFROM t\nWHERE a LIKE '%x'");
        var first = statement(0, "DELETE FROM t");
        var rules = List.of(
                ResolvedRule.defaults(new LeadingWildcardLikeRule()),
                ResolvedRule.defaults(new SelectStarRule()),
                ResolvedRule.defaults(new MissingWhereOnDeleteRule()),
                ResolvedRule.defaults(new DmlWithoutTransactionRule()),
                new ResolvedRule(new NoLineRule(), SqlReviewSeverity.WARN, Map.of()));

        var result = evaluator.evaluate(List.of(second, first), rules);

        assertThat(result.applicable()).isTrue();
        assertThat(result.findings()).extracting(SqlReviewFinding::statementIndex, SqlReviewFinding::lineNumber,
                        SqlReviewFinding::ruleId)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(0, 1, "dml_without_transaction"),
                        org.assertj.core.groups.Tuple.tuple(0, 1, "missing_where_on_delete"),
                        org.assertj.core.groups.Tuple.tuple(0, null, "no_line"),
                        org.assertj.core.groups.Tuple.tuple(1, 1, "select_star"),
                        org.assertj.core.groups.Tuple.tuple(1, 3, "leading_wildcard_like"),
                        org.assertj.core.groups.Tuple.tuple(1, null, "no_line"));
    }

    @Test
    void offRulesAreNeverAppliedAndSeverityIsRestamped() throws JSQLParserException {
        var counting = new NoLineRule();
        var rules = List.of(
                new ResolvedRule(counting, SqlReviewSeverity.OFF, Map.of()),
                new ResolvedRule(new SelectStarRule(), SqlReviewSeverity.BLOCK, Map.of()));

        var result = evaluator.evaluate(List.of(statement(0, "SELECT * FROM t")), rules);

        assertThat(counting.applications).isZero();
        assertThat(result.findings()).singleElement().satisfies(f -> {
            assertThat(f.ruleId()).isEqualTo("select_star");
            assertThat(f.severity()).isEqualTo(SqlReviewSeverity.BLOCK);
        });
        assertThat(result.hasBlocking()).isTrue();
    }

    @Test
    void aThrowingRuleIsSkippedForThatStatementOnly() throws JSQLParserException {
        var rules = List.of(
                ResolvedRule.defaults(new ExplodingRule()),
                ResolvedRule.defaults(new SelectStarRule()));

        var result = evaluator.evaluate(List.of(statement(0, "SELECT * FROM t"), statement(1, "SELECT * FROM u")),
                rules);

        assertThat(result.findings()).extracting(SqlReviewFinding::ruleId).containsExactly("select_star", "select_star");
    }

    @Test
    void envelopeStatementsCarryTheirOwnIndicesAndNoLines() {
        var parsed = new SqlParseResult(QueryType.DELETE, true,
                List.of("DELETE FROM a", "DELETE FROM b WHERE id = 1", "UPDATE c SET x = 1"), java.util.Set.of());
        var statements = SqlStatementParser.parse(parsed);
        var rules = List.of(
                ResolvedRule.defaults(new MissingWhereOnDeleteRule()),
                ResolvedRule.defaults(new DmlWithoutTransactionRule()));

        var result = evaluator.evaluate(statements, rules);

        assertThat(statements).hasSize(3);
        assertThat(result.findings()).singleElement().satisfies(f -> {
            assertThat(f.ruleId()).isEqualTo("missing_where_on_delete");
            assertThat(f.statementIndex()).isZero();
            assertThat(f.lineNumber()).isNull();
        });
    }

    @Test
    void noRulesOrNoStatementsIsClean() throws JSQLParserException {
        assertThat(evaluator.evaluate(List.of(statement(0, "SELECT * FROM t")), List.of()).findings()).isEmpty();
        assertThat(evaluator.evaluate(List.of(), List.of(ResolvedRule.defaults(new SelectStarRule()))).findings())
                .isEmpty();
        assertThat(evaluator.evaluate(List.of(statement(0, "SELECT 1")), List.of(ResolvedRule.defaults(new NullRule())))
                .findings()).isEmpty();
    }

    /** Fires on every statement with no line, to exercise the nulls-last ordering. */
    private static final class NoLineRule implements SqlRule {
        int applications;

        @Override
        public String ruleId() {
            return "no_line";
        }

        @Override
        public SqlRuleCategory category() {
            return SqlRuleCategory.STATEMENT_SAFETY;
        }

        @Override
        public SqlReviewSeverity defaultSeverity() {
            return SqlReviewSeverity.WARN;
        }

        @Override
        public List<SqlReviewFinding> apply(SqlRuleContext context, Map<String, List<String>> params) {
            applications++;
            return List.of(context.finding(this, null, Map.of()));
        }
    }

    private static final class ExplodingRule implements SqlRule {
        @Override
        public String ruleId() {
            return "explodes";
        }

        @Override
        public SqlRuleCategory category() {
            return SqlRuleCategory.STATEMENT_SAFETY;
        }

        @Override
        public SqlReviewSeverity defaultSeverity() {
            return SqlReviewSeverity.BLOCK;
        }

        @Override
        public List<SqlReviewFinding> apply(SqlRuleContext context, Map<String, List<String>> params) {
            throw new IllegalStateException("boom");
        }
    }

    private static final class NullRule implements SqlRule {
        @Override
        public String ruleId() {
            return "nulls";
        }

        @Override
        public SqlRuleCategory category() {
            return SqlRuleCategory.STATEMENT_SAFETY;
        }

        @Override
        public SqlReviewSeverity defaultSeverity() {
            return SqlReviewSeverity.WARN;
        }

        @Override
        public List<SqlReviewFinding> apply(SqlRuleContext context, Map<String, List<String>> params) {
            return null;
        }
    }
}
