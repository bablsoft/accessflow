package com.bablsoft.accessflow.sqlreview.internal.rules;

import com.bablsoft.accessflow.sqlreview.api.SqlReviewFinding;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewSeverity;
import com.bablsoft.accessflow.sqlreview.api.SqlRuleCategory;
import net.sf.jsqlparser.statement.select.AllColumns;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@code SELECT *} (or {@code t.*}) in a top-level select body — the statement, a set-operation
 * branch, or a CTE body. Subqueries are not inspected: {@code EXISTS (SELECT * …)} is idiomatic.
 */
public final class SelectStarRule implements SqlRule {

    public static final String ID = "select_star";

    @Override
    public String ruleId() {
        return ID;
    }

    @Override
    public SqlRuleCategory category() {
        return SqlRuleCategory.PERFORMANCE;
    }

    @Override
    public SqlReviewSeverity defaultSeverity() {
        return SqlReviewSeverity.WARN;
    }

    @Override
    public List<SqlReviewFinding> apply(SqlRuleContext context, Map<String, List<String>> params) {
        if (!(context.statement() instanceof Select select)) {
            return List.of();
        }
        var findings = new ArrayList<SqlReviewFinding>();
        for (var body : SelectBodies.topLevel(select)) {
            for (SelectItem<?> item : body.getSelectItems()) {
                if (item.getExpression() instanceof AllColumns star) {
                    findings.add(context.finding(this, star, Map.of()));
                }
            }
        }
        return findings;
    }
}
