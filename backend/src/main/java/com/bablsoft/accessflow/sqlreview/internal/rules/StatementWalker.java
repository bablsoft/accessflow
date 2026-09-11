package com.bablsoft.accessflow.sqlreview.internal.rules;

import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.operators.relational.LikeExpression;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.util.TablesNamesFinder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Walks an entire statement — select list, FROM / JOIN, WHERE, HAVING, UPDATE SET, INSERT…SELECT,
 * every nested subquery — collecting the constructs the expression-level rules care about (#862).
 * Piggybacks on {@link TablesNamesFinder}, which already knows how to traverse every JSqlParser
 * statement shape; the overrides record and delegate. JSqlParser raises
 * {@link UnsupportedOperationException} on a few exotic shapes: whatever was collected before that
 * point is kept, the rest is simply not seen.
 */
final class StatementWalker extends TablesNamesFinder<Void> {

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
            // Partial traversal; see class Javadoc.
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
        return super.visit(plainSelect, context);
    }
}
