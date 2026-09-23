package com.bablsoft.accessflow.proxy.internal;

import net.sf.jsqlparser.expression.AnalyticExpression;
import net.sf.jsqlparser.expression.ArrayExpression;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.JsonTableFunction;
import net.sf.jsqlparser.expression.XmlTableFunction;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.merge.Merge;
import net.sf.jsqlparser.statement.select.OrderByElement;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.statement.select.WithItem;
import net.sf.jsqlparser.statement.update.Update;
import net.sf.jsqlparser.util.TablesNamesFinder;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * One walk over a parsed statement that collects the referenced tables (for the allow-list and
 * row security) and flags constructs that write data from inside a query shape: a data-modifying
 * {@code WITH} item ({@code WITH d AS (DELETE … RETURNING *) SELECT …}) at any depth, and
 * {@code SELECT … INTO} (a PostgreSQL / SQL Server table, a temp table, or MySQL
 * {@code INTO OUTFILE / DUMPFILE}).
 *
 * <p>Tables are recorded here rather than taken from {@link TablesNamesFinder#getTables}, which
 * drops every name that matches <em>any</em> derived-table, LATERAL or {@code WITH} alias anywhere
 * in the statement — so {@code … EXISTS (SELECT 1 FROM (SELECT 1) AS secret)} erased the real
 * {@code secret}. Here a derived-table alias never hides a table (a FROM-clause name cannot resolve
 * to one), and a {@code WITH} name hides an unqualified table only inside the statement that
 * declares it, and only after its own body unless the list is {@code RECURSIVE}. A {@code WITH} item whose
 * declaring statement is not recognised hides nothing: counting a name as a table fails closed.
 * The walk also descends into expressions the finder skips — window {@code PARTITION BY}, a
 * function call's aggregate {@code ORDER BY} / {@code LIMIT} / {@code HAVING} / keyword and named
 * arguments, array subscripts, {@code TOP}, and {@code XMLTABLE} / {@code JSON_TABLE} used as a
 * FROM item.
 */
final class SqlStatementInspector extends TablesNamesFinder<Void> {

    private final Set<String> recorded = new HashSet<>();
    private final Deque<Scope> scopes = new ArrayDeque<>();
    private boolean writesData;

    private SqlStatementInspector() {
    }

    record Inspection(Set<String> tables, boolean writesData) {
    }

    private record Scope(Set<WithItem<?>> declared, Set<String> names) {
    }

    /**
     * @throws RuntimeException when JSqlParser cannot traverse the statement shape; the caller
     *     decides whether that is fatal
     */
    static Inspection inspect(Statement statement) {
        var inspector = new SqlStatementInspector();
        var tables = inspector.union(inspector.getTables(statement));
        return new Inspection(tables, inspector.writesData);
    }

    /**
     * Tables referenced anywhere in an expression, including the qualifiers of qualified columns
     * (the finder's expression mode), so a caller asking "does this touch a policied table" errs
     * towards yes.
     */
    static Set<String> tablesIn(Expression expression) {
        var inspector = new SqlStatementInspector();
        return inspector.union(inspector.getTables(expression));
    }

    private Set<String> union(Set<String> finderTables) {
        var out = new HashSet<>(recorded);
        if (finderTables != null) {
            out.addAll(finderTables);
        }
        return out;
    }

    @Override
    public <S> Void visit(Table table, S context) {
        if (!table.isTableVariable() && !hiddenByWithName(table)) {
            recorded.add(extractTableName(table));
        }
        return super.visit(table, context);
    }

    private boolean hiddenByWithName(Table table) {
        var name = SqlParserServiceImpl.normalizeIdentifier(extractTableName(table));
        if (name.indexOf('.') >= 0) {
            return false;
        }
        for (Scope scope : scopes) {
            if (scope.names().contains(name)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public <S> Void visit(WithItem<?> withItem, S context) {
        // getInsert()/getUpdate()/getDelete() cast blindly, so test the payload type instead.
        if (withItem.getStatement() != null
                && !(withItem.getStatement() instanceof ParenthesedSelect)) {
            writesData = true;
        }
        var owner = declaringScope(withItem);
        super.visit(withItem, context);
        if (owner != null) {
            declare(owner, withItem);
        }
        return null;
    }

    private Scope declaringScope(WithItem<?> withItem) {
        for (Scope scope : scopes) {
            if (scope.declared().contains(withItem)) {
                return scope;
            }
        }
        return null;
    }

    private static void declare(Scope scope, WithItem<?> withItem) {
        if (withItem.getAlias() != null && withItem.getAlias().getName() != null) {
            scope.names().add(SqlParserServiceImpl.normalizeIdentifier(withItem.getAlias().getName()));
        }
    }

    private <T> T scoped(List<WithItem<?>> withItems, Supplier<T> body) {
        Set<WithItem<?>> declared = Collections.newSetFromMap(new IdentityHashMap<>());
        if (withItems != null) {
            declared.addAll(withItems);
        }
        var scope = new Scope(declared, new HashSet<>());
        if (withItems != null && withItems.stream().anyMatch(WithItem::isRecursive)) {
            // WITH RECURSIVE: every name in the list is visible in every body of the list.
            withItems.forEach(item -> declare(scope, item));
        }
        scopes.push(scope);
        try {
            return body.get();
        } finally {
            scopes.pop();
        }
    }

    @Override
    public <S> Void visit(PlainSelect plainSelect, S context) {
        if ((plainSelect.getIntoTables() != null && !plainSelect.getIntoTables().isEmpty())
                || plainSelect.getIntoTempTable() != null
                || plainSelect.getMySqlSelectIntoClause() != null) {
            writesData = true;
        }
        return scoped(plainSelect.getWithItemsList(), () -> {
            super.visit(plainSelect, context);
            if (plainSelect.getTop() != null) {
                accept(plainSelect.getTop().getExpression(), context);
            }
            return null;
        });
    }

    @Override
    public <S> Void visit(Select select, S context) {
        // The statement-level entry walks the WITH items before dispatching to the select body.
        return scoped(select.getWithItemsList(), () -> super.visit(select, context));
    }

    @Override
    public <S> Void visit(SetOperationList list, S context) {
        return scoped(list.getWithItemsList(), () -> super.visit(list, context));
    }

    @Override
    public <S> Void visit(ParenthesedSelect select, S context) {
        return scoped(select.getWithItemsList(), () -> super.visit(select, context));
    }

    @Override
    public <S> Void visit(Insert insert, S context) {
        return scoped(insert.getWithItemsList(), () -> super.visit(insert, context));
    }

    @Override
    public <S> Void visit(Update update, S context) {
        return scoped(update.getWithItemsList(), () -> super.visit(update, context));
    }

    @Override
    public <S> Void visit(Delete delete, S context) {
        return scoped(delete.getWithItemsList(), () -> super.visit(delete, context));
    }

    @Override
    public <S> Void visit(Merge merge, S context) {
        return scoped(merge.getWithItemsList(), () -> super.visit(merge, context));
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
    public <S> Void visit(ArrayExpression array, S context) {
        super.visit(array, context);
        if (array.getStartIndexExpression() == null) {
            accept(array.getIndexExpression(), context);
        }
        return null;
    }

    @Override
    public <S> Void visit(Function function, S context) {
        // TablesNamesFinder.visit(TableFunction) calls visit(Function) with the static type, so an
        // XMLTABLE / JSON_TABLE in FROM would lose its PASSING / context expressions.
        if (function instanceof XmlTableFunction || function instanceof JsonTableFunction) {
            return function.accept(this, context);
        }
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
