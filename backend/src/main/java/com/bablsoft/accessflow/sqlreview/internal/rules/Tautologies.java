package com.bablsoft.accessflow.sqlreview.internal.rules;

import net.sf.jsqlparser.expression.BooleanValue;
import net.sf.jsqlparser.expression.DoubleValue;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.NotExpression;
import net.sf.jsqlparser.expression.Parenthesis;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.expression.operators.conditional.OrExpression;
import net.sf.jsqlparser.expression.operators.relational.ComparisonOperator;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.expression.operators.relational.GreaterThan;
import net.sf.jsqlparser.expression.operators.relational.GreaterThanEquals;
import net.sf.jsqlparser.expression.operators.relational.MinorThan;
import net.sf.jsqlparser.expression.operators.relational.MinorThanEquals;
import net.sf.jsqlparser.expression.operators.relational.NotEqualsTo;
import net.sf.jsqlparser.expression.operators.relational.ParenthesedExpressionList;
import net.sf.jsqlparser.schema.Column;

import java.math.BigDecimal;

/**
 * Recognises the predicates that are true for every row (#862): {@code TRUE}, a literal compared
 * to itself ({@code 1 = 1}, {@code 'a' = 'a'}), a column compared to itself ({@code x = x}), a
 * numeric-literal comparison that holds ({@code 1 <> 0}, {@code 2 > 1}), and {@code NOT FALSE}.
 * A WHERE is always true when the whole predicate is such a tautology or when any disjunct of its
 * top-level {@code OR} chain is — {@code id = 1 OR 1 = 1} — which is how {@code missing_where_on_*}
 * would otherwise be defeated. {@code AND}-ed tautologies are harmless and are left alone.
 */
final class Tautologies {

    private Tautologies() {
    }

    static boolean isAlwaysTrue(Expression expression) {
        var unwrapped = unwrap(expression);
        if (unwrapped == null) {
            return false;
        }
        if (unwrapped instanceof OrExpression or) {
            return isAlwaysTrue(or.getLeftExpression()) || isAlwaysTrue(or.getRightExpression());
        }
        return isTautology(unwrapped);
    }

    static boolean isTautology(Expression expression) {
        var unwrapped = unwrap(expression);
        return switch (unwrapped) {
            case null -> false;
            case BooleanValue bool -> bool.getValue();
            case NotExpression not -> isAlwaysFalse(not.getExpression());
            case EqualsTo eq -> sameOperand(unwrap(eq.getLeftExpression()), unwrap(eq.getRightExpression()))
                    || numericallyTrue(eq);
            case ComparisonOperator comparison -> numericallyTrue(comparison);
            default -> false;
        };
    }

    private static boolean isAlwaysFalse(Expression expression) {
        return unwrap(expression) instanceof BooleanValue bool && !bool.getValue();
    }

    /** {@code 1 <> 0}, {@code 2 > 1}, {@code 1 >= 1.0} — both sides numeric literals, evaluated. */
    private static boolean numericallyTrue(ComparisonOperator comparison) {
        var left = number(unwrap(comparison.getLeftExpression()));
        var right = number(unwrap(comparison.getRightExpression()));
        if (left == null || right == null) {
            return false;
        }
        int cmp = left.compareTo(right);
        return switch (comparison) {
            case EqualsTo ignored -> cmp == 0;
            case NotEqualsTo ignored -> cmp != 0;
            case GreaterThan ignored -> cmp > 0;
            case GreaterThanEquals ignored -> cmp >= 0;
            case MinorThan ignored -> cmp < 0;
            case MinorThanEquals ignored -> cmp <= 0;
            default -> false;
        };
    }

    private static BigDecimal number(Expression expression) {
        try {
            return switch (expression) {
                case LongValue value -> new BigDecimal(value.getStringValue());
                case DoubleValue value -> BigDecimal.valueOf(value.getValue());
                case null, default -> null;
            };
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /** Strips redundant parentheses: {@code ((1 = 1))} → {@code 1 = 1}. */
    static Expression unwrap(Expression expression) {
        var current = expression;
        while (true) {
            if (current instanceof Parenthesis parenthesis) {
                current = parenthesis.getExpression();
            } else if (current instanceof ParenthesedExpressionList<?> list && list.size() == 1
                    && list.get(0) instanceof Expression inner) {
                current = inner;
            } else {
                return current;
            }
        }
    }

    private static boolean sameOperand(Expression left, Expression right) {
        if (left == null || right == null || left.getClass() != right.getClass()) {
            return false;
        }
        boolean comparable = left instanceof LongValue || left instanceof DoubleValue
                || left instanceof StringValue || left instanceof BooleanValue || left instanceof Column;
        return comparable && left.toString().equals(right.toString());
    }
}
