package com.bablsoft.accessflow.sqlreview.internal.rules;

import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;

/** Parses standalone condition fixtures for the helper-level tests. */
final class RuleTestSupportExpressions {

    private RuleTestSupportExpressions() {
    }

    static Expression expression(String sql) {
        try {
            return CCJSqlParserUtil.parseCondExpression(sql);
        } catch (JSQLParserException ex) {
            throw new IllegalArgumentException("fixture does not parse: " + sql, ex);
        }
    }
}
