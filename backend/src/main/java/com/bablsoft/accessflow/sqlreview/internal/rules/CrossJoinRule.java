package com.bablsoft.accessflow.sqlreview.internal.rules;

import com.bablsoft.accessflow.sqlreview.api.SqlReviewFinding;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewSeverity;
import com.bablsoft.accessflow.sqlreview.api.SqlRuleCategory;
import net.sf.jsqlparser.statement.select.Join;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A Cartesian product anywhere in the statement, subqueries included. Detection lives in
 * {@link CartesianJoins}, shared with the custom-rule {@code join_without_condition} fact.
 */
public final class CrossJoinRule implements SqlRule {

    public static final String ID = "cross_join";

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
    public List<String> messageArgKeys() {
        return List.of("table");
    }

    @Override
    public List<SqlReviewFinding> apply(SqlRuleContext context, Map<String, List<String>> params) {
        var findings = new ArrayList<SqlReviewFinding>();
        for (Join join : CartesianJoins.find(StatementWalker.walk(context.statement()))) {
            findings.add(context.finding(this, join, Map.of("table", CartesianJoins.describe(join.getFromItem()))));
        }
        return findings;
    }
}
