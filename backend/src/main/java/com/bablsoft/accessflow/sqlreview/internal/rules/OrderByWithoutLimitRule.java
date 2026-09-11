package com.bablsoft.accessflow.sqlreview.internal.rules;

import com.bablsoft.accessflow.sqlreview.api.SqlReviewFinding;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewSeverity;
import com.bablsoft.accessflow.sqlreview.api.SqlRuleCategory;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.select.OrderByElement;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.update.Update;

import java.util.List;
import java.util.Map;

/**
 * {@code ORDER BY} with no row limit — the whole table is sorted to return every row. Covers
 * {@code SELECT} and the MySQL-style {@code UPDATE … ORDER BY} / {@code DELETE … ORDER BY} forms.
 */
public final class OrderByWithoutLimitRule implements SqlRule {

    public static final String ID = "order_by_without_limit";

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
        return switch (context.statement()) {
            case Select select when RowLimits.hasOrderBy(select) && !RowLimits.hasRowLimit(select) ->
                    List.of(context.finding(this, select, Map.of()));
            case Update update when ordered(update.getOrderByElements()) && update.getLimit() == null ->
                    List.of(context.finding(this, update.getTable(), Map.of()));
            case Delete delete when ordered(delete.getOrderByElements()) && delete.getLimit() == null ->
                    List.of(context.finding(this, delete.getTable(), Map.of()));
            default -> List.of();
        };
    }

    private static boolean ordered(List<OrderByElement> orderBy) {
        return orderBy != null && !orderBy.isEmpty();
    }
}
