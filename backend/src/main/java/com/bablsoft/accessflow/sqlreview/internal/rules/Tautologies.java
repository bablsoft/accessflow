package com.bablsoft.accessflow.sqlreview.internal.rules;

import net.sf.jsqlparser.expression.BooleanValue;
import net.sf.jsqlparser.expression.DoubleValue;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.Parenthesis;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.expression.operators.conditional.OrExpression;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.expression.operators.relational.ParenthesedExpressionList;
import net.sf.jsqlparser.schema.Column;

/**
 * Recognises the predicates that are true for every row (#862): {@code TRUE}, a literal compared
 * to itself ({@code 1 = 1}, {@code 'a' = 'a'}), and a column compared to itself ({@code x = x}).
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
            case EqualsTo eq -> sameOperand(unwrap(eq.getLeftExpression()), unwrap(eq.getRightExpression()));
            default -> false;
        };
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
