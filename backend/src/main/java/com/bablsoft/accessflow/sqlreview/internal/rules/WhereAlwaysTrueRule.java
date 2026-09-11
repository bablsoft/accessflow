package com.bablsoft.accessflow.sqlreview.internal.rules;

import com.bablsoft.accessflow.sqlreview.api.SqlReviewFinding;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewSeverity;
import com.bablsoft.accessflow.sqlreview.api.SqlRuleCategory;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.parser.ASTNodeAccess;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.update.Update;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A {@code WHERE} that is true for every row — {@code 1 = 1}, {@code TRUE}, {@code x = x}, or a
 * top-level {@code OR} disjunct that is. The rule that stops {@code missing_where_on_update} and
 * {@code missing_where_on_delete} being defeated by a decorative predicate.
 */
public final class WhereAlwaysTrueRule implements SqlRule {

    public static final String ID = "where_always_true";

    /** Longest predicate text stored in a finding's args; a long OR chain is elided beyond it. */
    static final int PREDICATE_MAX_LENGTH = 200;

    @Override
    public String ruleId() {
        return ID;
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
    public List<String> messageArgKeys() {
        return List.of("predicate");
    }

    @Override
    public List<SqlReviewFinding> apply(SqlRuleContext context, Map<String, List<String>> params) {
        var findings = new ArrayList<SqlReviewFinding>();
        switch (context.statement()) {
            case Select select -> SelectBodies.topLevel(select)
                    .forEach(body -> check(context, body.getWhere(), findings));
            case Update update -> check(context, update.getWhere(), findings);
            case Delete delete -> check(context, delete.getWhere(), findings);
            default -> { /* no WHERE clause to judge */ }
        }
        return findings;
    }

    private void check(SqlRuleContext context, Expression where, List<SqlReviewFinding> findings) {
        if (where != null && Tautologies.isAlwaysTrue(where)) {
            var anchor = where instanceof ASTNodeAccess node ? node : null;
            findings.add(context.finding(this, anchor, Map.of("predicate", elide(where.toString()))));
        }
    }

    private static String elide(String predicate) {
        return predicate.length() <= PREDICATE_MAX_LENGTH
                ? predicate
                : predicate.substring(0, PREDICATE_MAX_LENGTH - 1) + "…";
    }
}
