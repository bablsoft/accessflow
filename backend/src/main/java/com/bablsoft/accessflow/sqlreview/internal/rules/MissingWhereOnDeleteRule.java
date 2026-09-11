package com.bablsoft.accessflow.sqlreview.internal.rules;

import com.bablsoft.accessflow.sqlreview.api.SqlReviewFinding;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewSeverity;
import com.bablsoft.accessflow.sqlreview.api.SqlRuleCategory;
import net.sf.jsqlparser.statement.delete.Delete;

import java.util.List;
import java.util.Map;

/** {@code DELETE} with no {@code WHERE} — every row goes. */
public final class MissingWhereOnDeleteRule implements SqlRule {

    public static final String ID = "missing_where_on_delete";

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
        return List.of("table");
    }

    @Override
    public List<SqlReviewFinding> apply(SqlRuleContext context, Map<String, List<String>> params) {
        if (context.statement() instanceof Delete delete && delete.getWhere() == null) {
            return List.of(context.finding(this, delete.getTable(),
                    Map.of("table", TableNames.normalize(delete.getTable()))));
        }
        return List.of();
    }
}
