package com.bablsoft.accessflow.sqlreview.internal.rules;

import com.bablsoft.accessflow.sqlreview.api.SqlReviewFinding;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewSeverity;
import com.bablsoft.accessflow.sqlreview.api.SqlRuleCategory;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;

import java.util.List;
import java.util.Map;

/**
 * A {@code SELECT} reading from a table with no {@code LIMIT} / {@code TOP} / {@code FETCH FIRST}.
 * A table-less {@code SELECT 1} (the usual connectivity probe) returns one row and is skipped.
 */
public final class MissingLimitOnSelectRule implements SqlRule {

    public static final String ID = "missing_limit_on_select";

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
        if (select instanceof PlainSelect plain && plain.getFromItem() == null) {
            return List.of();
        }
        if (RowLimits.hasRowLimit(select)) {
            return List.of();
        }
        return List.of(context.finding(this, select, Map.of()));
    }
}
