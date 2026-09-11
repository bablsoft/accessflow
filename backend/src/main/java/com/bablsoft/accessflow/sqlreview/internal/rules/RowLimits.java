package com.bablsoft.accessflow.sqlreview.internal.rules;

import net.sf.jsqlparser.statement.select.Limit;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SetOperationList;

/**
 * Row-limit detection across dialects (#862): {@code LIMIT n}, SQL Server {@code TOP n}, and
 * {@code FETCH FIRST n ROWS ONLY} all count; {@code LIMIT ALL} and a bare {@code OFFSET} do not.
 * A set operation's trailing clauses may be parsed onto its last branch, so both are checked.
 */
final class RowLimits {

    private RowLimits() {
    }

    static boolean hasRowLimit(Select select) {
        if (select == null) {
            return false;
        }
        if (isRealLimit(select.getLimit()) || select.getFetch() != null) {
            return true;
        }
        return switch (select) {
            case PlainSelect plain -> plain.getTop() != null;
            case SetOperationList set -> hasRowLimit(lastBranch(set));
            case ParenthesedSelect parenthesed -> hasRowLimit(parenthesed.getSelect());
            default -> false;
        };
    }

    static boolean hasOrderBy(Select select) {
        if (select == null) {
            return false;
        }
        if (select.getOrderByElements() != null && !select.getOrderByElements().isEmpty()) {
            return true;
        }
        return switch (select) {
            case SetOperationList set -> hasOrderBy(lastBranch(set));
            case ParenthesedSelect parenthesed -> hasOrderBy(parenthesed.getSelect());
            default -> false;
        };
    }

    private static boolean isRealLimit(Limit limit) {
        return limit != null && !limit.isLimitAll() && !limit.isLimitNull();
    }

    private static Select lastBranch(SetOperationList set) {
        var branches = set.getSelects();
        return branches == null || branches.isEmpty() ? null : branches.get(branches.size() - 1);
    }
}
