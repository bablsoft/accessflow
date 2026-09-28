package com.bablsoft.accessflow.sqlreview.internal.rules;

import net.sf.jsqlparser.expression.DateUnitExpression;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.schema.Column;

/**
 * A column reference in an operand position. Since JSqlParser 5.4 a bare, unqualified {@code year},
 * {@code month}, {@code day}, {@code hour}, {@code minute} or {@code second} parses as a
 * {@link DateUnitExpression} rather than a {@link Column} (#1080); in a comparison operand it can
 * only be that bare column, so it counts as an unqualified one.
 */
final class ColumnLike {

    private ColumnLike() {
    }

    /** Only for comparison operands — elsewhere ({@code DATEDIFF(day, a, b)}) a date unit is not a column. */
    static boolean isColumn(Expression expression) {
        return expression instanceof Column || expression instanceof DateUnitExpression;
    }

    /** The normalised table qualifier of a column reference; {@code null} when unqualified or not a column. */
    static String qualifier(Expression expression) {
        if (expression instanceof Column column && column.getTable() != null) {
            return TableNames.normalize(column.getTable());
        }
        return null;
    }
}
