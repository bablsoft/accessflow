package com.bablsoft.accessflow.sqlreview.internal.rules;

import com.bablsoft.accessflow.sqlreview.api.SqlReviewFinding;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewSeverity;
import com.bablsoft.accessflow.sqlreview.api.SqlRuleCategory;
import net.sf.jsqlparser.statement.update.Update;

import java.util.List;
import java.util.Map;

/** {@code UPDATE} with no {@code WHERE} — every row is rewritten. */
public final class MissingWhereOnUpdateRule implements SqlRule {

    public static final String ID = "missing_where_on_update";

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
        if (context.statement() instanceof Update update && update.getWhere() == null) {
            return List.of(context.finding(this, update.getTable(),
                    Map.of("table", TableNames.normalize(update.getTable()))));
        }
        return List.of();
    }
}
