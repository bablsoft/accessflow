package com.bablsoft.accessflow.proxy.internal;

import net.sf.jsqlparser.expression.AnalyticExpression;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.OrderByElement;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.WithItem;
import net.sf.jsqlparser.util.TablesNamesFinder;

import java.util.Set;

/**
 * One walk over a parsed statement that collects the referenced tables (for the allow-list) and
 * flags constructs that write data from inside a query shape: a data-modifying {@code WITH} item
 * ({@code WITH d AS (DELETE … RETURNING *) SELECT …}) at any depth, and {@code SELECT … INTO}
 * (a PostgreSQL / SQL Server table, a temp table, or MySQL {@code INTO OUTFILE / DUMPFILE}).
 * Extends {@link TablesNamesFinder} so both answers come from the same traversal, and adds the
 * expressions the finder does not descend into — window {@code PARTITION BY}, and a function
 * call's aggregate {@code ORDER BY}, {@code LIMIT}, {@code HAVING}, keyword and named arguments —
 * so a subquery hidden there still reaches the allow-list.
 */
final class SqlStatementInspector extends TablesNamesFinder<Void> {

    private boolean writesData;

    private SqlStatementInspector() {
    }

    record Inspection(Set<String> tables, boolean writesData) {
    }

    /**
     * @throws RuntimeException when JSqlParser cannot traverse the statement shape; the caller
     *     decides whether that is fatal
     */
    static Inspection inspect(Statement statement) {
        var inspector = new SqlStatementInspector();
        Set<String> tables = inspector.getTables(statement);
        return new Inspection(tables == null ? Set.of() : tables, inspector.writesData);
    }

    @Override
    public <S> Void visit(WithItem<?> withItem, S context) {
        // getInsert()/getUpdate()/getDelete() cast blindly, so test the payload type instead.
        if (withItem.getStatement() != null
                && !(withItem.getStatement() instanceof ParenthesedSelect)) {
            writesData = true;
        }
        return super.visit(withItem, context);
    }

    @Override
    public <S> Void visit(PlainSelect plainSelect, S context) {
        if ((plainSelect.getIntoTables() != null && !plainSelect.getIntoTables().isEmpty())
                || plainSelect.getIntoTempTable() != null
                || plainSelect.getMySqlSelectIntoClause() != null) {
            writesData = true;
        }
        return super.visit(plainSelect, context);
    }

    @Override
    public <S> Void visit(AnalyticExpression analytic, S context) {
        super.visit(analytic, context);
        if (analytic.getWindowDefinition() != null) {
            accept(analytic.getPartitionExpressionList(), context);
        }
        return null;
    }

    @Override
    public <S> Void visit(Function function, S context) {
        super.visit(function, context);
        accept(function.getNamedParameters(), context);
        accept(function.getKeep(), context);
        if (function.getAttribute() instanceof Expression attribute) {
            attribute.accept(this, context);
        }
        if (function.getKeywordArguments() != null) {
            for (Function.KeywordArgument argument : function.getKeywordArguments()) {
                accept(argument.getExpression(), context);
            }
        }
        if (function.getOrderByElements() != null) {
            for (OrderByElement element : function.getOrderByElements()) {
                accept(element.getExpression(), context);
            }
        }
        if (function.getLimit() != null) {
            accept(function.getLimit().getRowCount(), context);
            accept(function.getLimit().getOffset(), context);
        }
        if (function.getHavingClause() != null) {
            accept(function.getHavingClause().getExpression(), context);
        }
        return null;
    }

    private <S> void accept(Expression expression, S context) {
        if (expression != null) {
            expression.accept(this, context);
        }
    }
}
