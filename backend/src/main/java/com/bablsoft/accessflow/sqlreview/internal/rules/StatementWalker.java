package com.bablsoft.accessflow.sqlreview.internal.rules;

import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.operators.relational.LikeExpression;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.select.Limit;
import net.sf.jsqlparser.statement.select.Offset;
import net.sf.jsqlparser.statement.select.OrderByElement;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.update.Update;
import net.sf.jsqlparser.util.TablesNamesFinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Walks an entire statement — select list, FROM / JOIN, WHERE, HAVING, GROUP BY, ORDER BY, LIMIT /
 * OFFSET, UPDATE SET, INSERT…SELECT, every nested subquery — collecting the constructs the
 * expression-level rules care about (#862). Piggybacks on {@link TablesNamesFinder}, which already
 * knows how to traverse every JSqlParser statement shape; the overrides record and delegate, and
 * add the ordering / grouping / limit expressions the finder skips because they cannot name tables
 * (a {@code disallowed_function} hides just as well in {@code ORDER BY pg_sleep(10)}). JSqlParser raises
 * {@link UnsupportedOperationException} on a few exotic shapes: whatever was collected before that
 * point is kept, the rest is simply not seen.
 */
final class StatementWalker extends TablesNamesFinder<Void> {

    private static final Logger log = LoggerFactory.getLogger(StatementWalker.class);

    private final List<Function> functions = new ArrayList<>();
    private final List<LikeExpression> likes = new ArrayList<>();
    private final List<PlainSelect> plainSelects = new ArrayList<>();
    private final List<Table> tables = new ArrayList<>();

    private StatementWalker() {
    }

    static StatementWalker walk(Statement statement) {
        var walker = new StatementWalker();
        try {
            walker.getTables(statement);
        } catch (RuntimeException ex) {
            log.debug("SQL review: partial walk of a {} statement — {}", statement.getClass().getSimpleName(),
                    ex.toString());
        }
        return walker;
    }

    /** Every function call, outermost first, in source order. */
    List<Function> functions() {
        return Collections.unmodifiableList(functions);
    }

    /** Every {@code LIKE} / {@code ILIKE} (negated or not), in source order. */
    List<LikeExpression> likes() {
        return Collections.unmodifiableList(likes);
    }

    /** Every select body, including subqueries and CTE bodies, in traversal order. */
    List<PlainSelect> plainSelects() {
        return Collections.unmodifiableList(plainSelects);
    }

    /** Every table reference in a FROM / JOIN / target position, in traversal order. */
    List<Table> tables() {
        return Collections.unmodifiableList(tables);
    }

    @Override
    public <S> Void visit(Table table, S context) {
        tables.add(table);
        return super.visit(table, context);
    }

    @Override
    public <S> Void visit(Function function, S context) {
        functions.add(function);
        return super.visit(function, context);
    }

    @Override
    public <S> Void visit(LikeExpression like, S context) {
        likes.add(like);
        return super.visit(like, context);
    }

    @Override
    public <S> Void visit(PlainSelect plainSelect, S context) {
        plainSelects.add(plainSelect);
        var result = super.visit(plainSelect, context);
        if (plainSelect.getGroupBy() != null && plainSelect.getGroupBy().getGroupByExpressionList() != null) {
            plainSelect.getGroupBy().getGroupByExpressionList().accept(this, context);
        }
        visitTrailing(plainSelect, context);
        return result;
    }

    @Override
    public <S> Void visit(Update update, S context) {
        var result = super.visit(update, context);
        visitOrderBy(update.getOrderByElements(), context);
        visitLimit(update.getLimit(), context);
        return result;
    }

    @Override
    public <S> Void visit(Delete delete, S context) {
        var result = super.visit(delete, context);
        visitOrderBy(delete.getOrderByElements(), context);
        visitLimit(delete.getLimit(), context);
        return result;
    }

    private <S> void visitTrailing(Select select, S context) {
        visitOrderBy(select.getOrderByElements(), context);
        visitLimit(select.getLimit(), context);
        visitOffset(select.getOffset(), context);
    }

    private <S> void visitOrderBy(List<OrderByElement> orderBy, S context) {
        if (orderBy == null) {
            return;
        }
        for (OrderByElement element : orderBy) {
            if (element.getExpression() != null) {
                element.getExpression().accept(this, context);
            }
        }
    }

    private <S> void visitLimit(Limit limit, S context) {
        if (limit == null) {
            return;
        }
        if (limit.getRowCount() != null) {
            limit.getRowCount().accept(this, context);
        }
        if (limit.getOffset() != null) {
            limit.getOffset().accept(this, context);
        }
    }

    private <S> void visitOffset(Offset offset, S context) {
        if (offset != null && offset.getOffset() != null) {
            offset.getOffset().accept(this, context);
        }
    }
}
