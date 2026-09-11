package com.bablsoft.accessflow.sqlreview.internal.rules;

import com.bablsoft.accessflow.sqlreview.api.SqlReviewFinding;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;

import java.util.List;
import java.util.Map;

/** Parses fixtures and applies a rule with no params — the shape every rule test shares. */
final class RuleTestSupport {

    private RuleTestSupport() {
    }

    static Statement parse(String sql) {
        try {
            return CCJSqlParserUtil.parse(sql);
        } catch (JSQLParserException ex) {
            throw new IllegalArgumentException("fixture does not parse: " + sql, ex);
        }
    }

    static SqlRuleContext context(String sql) {
        return SqlRuleContext.of(parse(sql));
    }

    /** A statement as it arrives from a {@code BEGIN…COMMIT} envelope: deparsed, lines unknown. */
    static SqlRuleContext envelope(int index, String sql) {
        return new SqlRuleContext(index, parse(sql), true, false);
    }

    static List<SqlReviewFinding> apply(SqlRule rule, String sql) {
        return rule.apply(context(sql), Map.of());
    }
}
