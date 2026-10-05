package com.bablsoft.accessflow.sqlreview.internal.rules;

import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.statement.update.Update;

import java.util.ArrayList;
import java.util.List;

/**
 * The top-level {@code WHERE} clauses of a statement, shared by {@code where_always_true} and the
 * custom-rule {@code has_where} / {@code where_always_true} facts (#1009).
 */
final class WhereClauses {

    private WhereClauses() {
    }

    /**
     * The non-null {@code WHERE} predicates that are true for every row: of every top-level select
     * body (set-operation branches and CTE bodies, see {@link SelectBodies}), or of an
     * {@code UPDATE} / {@code DELETE}.
     */
    static List<Expression> alwaysTrue(Statement statement) {
        var wheres = new ArrayList<Expression>();
        switch (statement) {
            case Select select -> SelectBodies.topLevel(select).forEach(body -> wheres.add(body.getWhere()));
            case Update update -> wheres.add(update.getWhere());
            case Delete delete -> wheres.add(delete.getWhere());
            default -> { /* no WHERE clause to judge */ }
        }
        var out = new ArrayList<Expression>();
        for (Expression where : wheres) {
            if (where != null && Tautologies.isAlwaysTrue(where)) {
                out.add(where);
            }
        }
        return out;
    }

    /**
     * Whether the statement is filtered: an {@code UPDATE} / {@code DELETE} with a {@code WHERE},
     * or a {@code SELECT} whose every set-operation branch has one. CTE bodies and subqueries are
     * not consulted. Any other statement is unfiltered.
     */
    static boolean hasWhere(Statement statement) {
        return switch (statement) {
            case Select select -> selectHasWhere(select);
            case Update update -> update.getWhere() != null;
            case Delete delete -> delete.getWhere() != null;
            default -> false;
        };
    }

    private static boolean selectHasWhere(Select select) {
        return switch (select) {
            case PlainSelect plain -> plain.getWhere() != null;
            case SetOperationList set -> set.getSelects() != null && !set.getSelects().isEmpty()
                    && set.getSelects().stream().allMatch(WhereClauses::selectHasWhere);
            case ParenthesedSelect parenthesed -> parenthesed.getSelect() != null
                    && selectHasWhere(parenthesed.getSelect());
            default -> false;
        };
    }
}
