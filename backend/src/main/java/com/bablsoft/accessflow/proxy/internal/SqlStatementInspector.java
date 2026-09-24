package com.bablsoft.accessflow.proxy.internal;

import com.bablsoft.accessflow.core.api.ColumnReference;

import net.sf.jsqlparser.expression.AnalyticExpression;
import net.sf.jsqlparser.expression.ArrayExpression;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.JsonExpression;
import net.sf.jsqlparser.expression.JsonTableFunction;
import net.sf.jsqlparser.expression.KeepExpression;
import net.sf.jsqlparser.expression.MySQLGroupConcat;
import net.sf.jsqlparser.expression.XmlTableFunction;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.expression.operators.relational.FullTextSearch;
import net.sf.jsqlparser.expression.operators.relational.IsUnknownExpression;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.merge.Merge;
import net.sf.jsqlparser.statement.piped.FromQuery;
import net.sf.jsqlparser.statement.select.AllColumns;
import net.sf.jsqlparser.statement.select.AllTableColumns;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.OrderByElement;
import net.sf.jsqlparser.statement.select.ParenthesedFromItem;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.statement.select.TableStatement;
import net.sf.jsqlparser.statement.select.WithItem;
import net.sf.jsqlparser.statement.update.Update;
import net.sf.jsqlparser.util.TablesNamesFinder;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
 * arguments, JSON path segments ({@code a:(SELECT …)}), Oracle {@code KEEP}, MySQL {@code GROUP_CONCAT}, {@code IS UNKNOWN}, array subscripts
 * and slices, {@code TOP}, and {@code XMLTABLE} / {@code JSON_TABLE} used as a FROM item.
 *
 * <p>The same walk records every referenced column for column-level authorization (#935). Each
 * {@code SELECT} / {@code UPDATE} / {@code DELETE} / {@code INSERT} pushes its FROM scope (alias →
 * item). A qualified column resolves through the scopes, innermost first: to a real table, or — for
 * a derived table or {@code WITH} name — to nothing, because the inner query is walked on its own
 * and its columns are recorded at the source. An unknown qualifier (a pseudo-table such as SQL
 * Server's {@code inserted} / {@code deleted} or PostgreSQL's {@code excluded}) takes itself and
 * every real table in scope as candidates, and so does an unqualified column: both fail closed.
 * {@code *} is a wildcard over the current scope's real tables, {@code t.*} over one table, and so
 * are the shapes that return whole rows without naming a column — an {@code INSERT} without a column
 * list, {@code TABLE t}, a pipe-syntax {@code FROM t |> …}, a bare alias or table name used as a
 * value ({@code SELECT u}, {@code row_to_json(u)}, {@code (u).col}), and a table alias that renames
 * columns by position ({@code users AS u(a, b)}). {@code COUNT(*)} is not a column reference,
 * but any other function's {@code *} is, and a {@code NATURAL} join is a wildcard over the tables
 * it joins.
 */
final class SqlStatementInspector extends TablesNamesFinder<Void> {

    /**
     * Built-in PostgreSQL functions that take a whole row, so functional notation ({@code u.f} for
     * {@code f(u)}) turns them into a whole-row read. Limited to names no table uses as a column;
     * the string casts ({@code u.text}, {@code u.name}) and user-defined functions over the row type
     * look exactly like real columns and are out of reach without the catalog.
     */
    private static final Set<String> ROW_FUNCTIONS = Set.of("row_to_json", "to_json", "to_jsonb",
            "hstore", "json_build_array", "jsonb_build_array", "record_out");

    /** Aggregates whose {@code *} counts rows rather than reading columns. */
    private static final Set<String> ROW_COUNTING_FUNCTIONS = Set.of("count", "count_big");

    private final Set<String> recorded = new HashSet<>();
    private final Deque<Scope> scopes = new ArrayDeque<>();
    private final Deque<FromScope> fromScopes = new ArrayDeque<>();
    private final Set<ColumnReference> columns = new LinkedHashSet<>();
    private int functionDepth;
    private boolean writesData;
    private boolean misparsed;

    private SqlStatementInspector() {
    }

    /**
     * @param misparsed JSqlParser read a parenthesised {@code (TABLE t)} FROM item as a table named
     *     {@code TABLE} aliased {@code t}, so the real table is hidden from every check; the caller
     *     refuses the statement
     */
    record Inspection(Set<String> tables, boolean writesData, Set<ColumnReference> columns,
                      boolean misparsed) {
    }

    private record Scope(Set<WithItem<?>> declared, Set<String> names) {
    }

    /** One statement's FROM items by every name a column may qualify them with. */
    private record FromScope(Map<String, FromItem> byName, List<Table> tables) {

        static FromScope empty() {
            return new FromScope(new HashMap<>(), new ArrayList<>());
        }
    }

    /**
     * @throws RuntimeException when JSqlParser cannot traverse the statement shape; the caller
     *     decides whether that is fatal
     */
    static Inspection inspect(Statement statement) {
        var inspector = new SqlStatementInspector();
        var tables = inspector.union(inspector.getTables(statement));
        return new Inspection(tables, inspector.writesData, Set.copyOf(inspector.columns),
                inspector.misparsed);
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
        // Only the bare keyword: a quoted "table" keeps its quotes in the qualified name.
        if ("TABLE".equalsIgnoreCase(table.getFullyQualifiedName())) {
            misparsed = true;
        }
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
        var scope = FromScope.empty();
        addFromItem(scope, plainSelect.getFromItem());
        addJoins(scope, plainSelect.getJoins());
        return scoped(plainSelect.getWithItemsList(), () -> fromScoped(scope, () -> {
            super.visit(plainSelect, context);
            if (plainSelect.getTop() != null) {
                accept(plainSelect.getTop().getExpression(), context);
            }
            recordUsingColumns(plainSelect.getJoins());
            return null;
        }));
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
        var scope = FromScope.empty();
        addFromItem(scope, insert.getTable());
        return scoped(insert.getWithItemsList(), () -> fromScoped(scope, () -> {
            super.visit(insert, context);
            recordInsertTarget(insert);
            return null;
        }));
    }

    @Override
    public <S> Void visit(Update update, S context) {
        var scope = FromScope.empty();
        if (!update.isTargetTableAlias()) {
            addFromItem(scope, update.getTable());
        }
        addJoins(scope, update.getStartJoins());
        addFromItem(scope, update.getFromItem());
        addJoins(scope, update.getJoins());
        return scoped(update.getWithItemsList(), () -> fromScoped(scope, () -> {
            super.visit(update, context);
            recordUsingColumns(update.getJoins());
            return null;
        }));
    }

    @Override
    public <S> Void visit(Delete delete, S context) {
        var scope = FromScope.empty();
        addFromItem(scope, delete.getTable());
        if (delete.getUsingFromItemList() != null) {
            delete.getUsingFromItemList().forEach(item -> addFromItem(scope, item));
        }
        addJoins(scope, delete.getJoins());
        return scoped(delete.getWithItemsList(), () -> fromScoped(scope, () -> {
            super.visit(delete, context);
            recordUsingColumns(delete.getJoins());
            return null;
        }));
    }

    @Override
    public <S> Void visit(Column column, S context) {
        super.visit(column, context);
        var qualifier = column.getTable() == null ? null : column.getTable().getName();
        var name = SqlParserServiceImpl.normalizeIdentifier(column.getColumnName());
        if (name.isBlank()) {
            return null;
        }
        if (qualifier == null || qualifier.isBlank()) {
            columns.add(new ColumnReference(unqualifiedCandidates(), name));
            recordWholeRowReference(name);
            return null;
        }
        var candidates = resolveQualifier(column.getTable());
        if (candidates != null) {
            columns.add(new ColumnReference(candidates, name));
            if (ROW_FUNCTIONS.contains(name)) {
                // PostgreSQL reads u.f as f(u) when u has no column f: u.to_jsonb is the row.
                columns.add(ColumnReference.wildcard(candidates));
            }
        }
        return null;
    }

    @Override
    public <S> Void visit(AllColumns allColumns, S context) {
        super.visit(allColumns, context);
        if (functionDepth == 0 && !fromScopes.isEmpty()) {
            var tables = realTables(fromScopes.peek());
            if (!tables.isEmpty()) {
                columns.add(ColumnReference.wildcard(tables));
            }
        }
        return null;
    }

    @Override
    public <S> Void visit(AllTableColumns allTableColumns, S context) {
        super.visit(allTableColumns, context);
        if (allTableColumns.getTable() != null) {
            var candidates = resolveQualifier(allTableColumns.getTable());
            if (candidates != null) {
                columns.add(ColumnReference.wildcard(candidates));
            }
        }
        return null;
    }

    private <T> T fromScoped(FromScope scope, Supplier<T> body) {
        // A subquery inside a function argument is a query of its own: its * is a column list.
        int savedDepth = functionDepth;
        functionDepth = 0;
        fromScopes.push(scope);
        try {
            return body.get();
        } finally {
            fromScopes.pop();
            functionDepth = savedDepth;
        }
    }

    private void addJoins(FromScope scope, List<Join> joins) {
        if (joins != null) {
            joins.forEach(join -> addFromItem(scope, join.getFromItem()));
        }
    }

    private void addFromItem(FromScope scope, FromItem item) {
        switch (item) {
            case null -> {
                // no FROM clause
            }
            case Table table -> {
                if (table.isTableVariable()) {
                    return;
                }
                scope.tables().add(table);
                if (table.getAlias() != null && table.getAlias().getAliasColumns() != null
                        && !table.getAlias().getAliasColumns().isEmpty()) {
                    // u(a, b) renames columns by position, so a name no longer says which column.
                    columns.add(ColumnReference.wildcard(Set.of(tableName(table))));
                }
                if (table.getAlias() != null && table.getAlias().getName() != null) {
                    scope.byName().put(key(table.getAlias().getName()), table);
                } else {
                    scope.byName().put(key(table.getName()), table);
                    scope.byName().put(key(table.getFullyQualifiedName()), table);
                }
            }
            case ParenthesedFromItem parenthesed -> {
                addFromItem(scope, parenthesed.getFromItem());
                addJoins(scope, parenthesed.getJoins());
            }
            default -> {
                if (item.getAlias() != null && item.getAlias().getName() != null) {
                    scope.byName().put(key(item.getAlias().getName()), item);
                }
            }
        }
    }

    /**
     * @return the real table a qualifier names, or {@code null} when it names a derived table or a
     *     {@code WITH} item whose own body records its columns
     */
    private Set<String> resolveQualifier(Table qualifier) {
        var fullName = key(qualifier.getFullyQualifiedName());
        var shortName = key(qualifier.getName());
        for (FromScope scope : fromScopes) {
            var item = scope.byName().get(fullName);
            if (item == null) {
                item = scope.byName().get(shortName);
            }
            if (item instanceof Table table) {
                return hiddenByWithName(table) ? null : Set.of(tableName(table));
            }
            if (item != null) {
                return null;
            }
        }
        if (fullName.indexOf('.') < 0 && hiddenByWithName(qualifier)) {
            return null;
        }
        var candidates = unqualifiedCandidates();
        candidates.add(fullName);
        return candidates;
    }

    /**
     * PostgreSQL reads a bare alias or table name as the whole row ({@code SELECT u},
     * {@code row_to_json(u)}, {@code (u).col}), so a name that resolves to a real table in scope is
     * a wildcard over that table too.
     */
    private void recordWholeRowReference(String name) {
        for (FromScope scope : fromScopes) {
            var item = scope.byName().get(name);
            if (item instanceof Table table) {
                if (!hiddenByWithName(table)) {
                    columns.add(ColumnReference.wildcard(Set.of(tableName(table))));
                }
                return;
            }
            if (item != null) {
                return;
            }
        }
    }

    @Override
    public <S> Void visit(TableStatement tableStatement, S context) {
        super.visit(tableStatement, context);
        var table = tableStatement.getTable();
        if (table != null && !hiddenByWithName(table)) {
            columns.add(ColumnReference.wildcard(Set.of(tableName(table))));
        }
        return null;
    }

    @Override
    public <S> Void visit(FromQuery fromQuery, S context) {
        var scope = FromScope.empty();
        addFromItem(scope, fromQuery.getFromItem());
        addJoins(scope, fromQuery.getJoins());
        return scoped(fromQuery.getWithItemsList(), () -> fromScoped(scope, () -> {
            super.visit(fromQuery, context);
            // Pipe operators can project, extend or pass rows through untouched; treat the source
            // as read whole rather than follow each operator.
            var tables = realTables(scope);
            if (!tables.isEmpty()) {
                columns.add(ColumnReference.wildcard(tables));
            }
            return null;
        }));
    }

    @Override
    public <S> Void visit(FullTextSearch fullTextSearch, S context) {
        super.visit(fullTextSearch, context);
        if (fullTextSearch.getMatchColumns() != null) {
            fullTextSearch.getMatchColumns().forEach(column -> column.accept(this, context));
        }
        accept(fullTextSearch.getAgainstValue(), context);
        return null;
    }

    private Set<String> unqualifiedCandidates() {
        var out = new HashSet<String>();
        for (FromScope scope : fromScopes) {
            out.addAll(realTables(scope));
        }
        return out;
    }

    private Set<String> realTables(FromScope scope) {
        var out = new HashSet<String>();
        for (Table table : scope.tables()) {
            if (!hiddenByWithName(table)) {
                out.add(tableName(table));
            }
        }
        return out;
    }

    private void recordUsingColumns(List<Join> joins) {
        if (joins == null) {
            return;
        }
        for (Join join : joins) {
            if (join.isNatural()) {
                // NATURAL joins on every same-named column, so it compares columns it never names.
                var tables = unqualifiedCandidates();
                if (!tables.isEmpty()) {
                    columns.add(ColumnReference.wildcard(tables));
                }
            }
            if (join.getUsingColumns() != null) {
                for (Column column : join.getUsingColumns()) {
                    var name = SqlParserServiceImpl.normalizeIdentifier(column.getColumnName());
                    if (!name.isBlank()) {
                        columns.add(new ColumnReference(unqualifiedCandidates(), name));
                    }
                }
            }
        }
    }

    private void recordInsertTarget(Insert insert) {
        var target = insert.getTable();
        if (target == null || target.isTableVariable() || hiddenByWithName(target)) {
            return;
        }
        var targetName = Set.of(tableName(target));
        var insertColumns = insert.getColumns();
        if (insertColumns == null || insertColumns.isEmpty()) {
            if (insert.getSetUpdateSets() == null || insert.getSetUpdateSets().isEmpty()) {
                columns.add(ColumnReference.wildcard(targetName));
            }
            return;
        }
        for (Column column : insertColumns) {
            var name = SqlParserServiceImpl.normalizeIdentifier(column.getColumnName());
            if (!name.isBlank()) {
                columns.add(new ColumnReference(targetName, name));
            }
        }
    }

    private String tableName(Table table) {
        return SqlParserServiceImpl.normalizeIdentifier(extractTableName(table));
    }

    private static String key(String identifier) {
        return SqlParserServiceImpl.normalizeIdentifier(identifier);
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
        // Not delegated: the finder visits the index only for a slice, where it is null.
        accept(array.getObjExpression(), context);
        accept(array.getIndexExpression(), context);
        accept(array.getStartIndexExpression(), context);
        accept(array.getStopIndexExpression(), context);
        return null;
    }

    @Override
    public <S> Void visit(JsonExpression json, S context) {
        super.visit(json, context);
        if (json.getIdents() != null) {
            json.getIdents().forEach(ident -> accept(ident, context));
        }
        return null;
    }

    @Override
    public <S> Void visit(KeepExpression keep, S context) {
        acceptOrderBy(keep.getOrderByElements(), context);
        return null;
    }

    @Override
    public <S> Void visit(MySQLGroupConcat groupConcat, S context) {
        accept(groupConcat.getExpressionList(), context);
        acceptOrderBy(groupConcat.getOrderByElements(), context);
        return null;
    }

    @Override
    public <S> Void visit(IsUnknownExpression isUnknown, S context) {
        accept(isUnknown.getLeftExpression(), context);
        return null;
    }

    @Override
    public <S> Void visit(Function function, S context) {
        // TablesNamesFinder.visit(TableFunction) calls visit(Function) with the static type, so an
        // XMLTABLE / JSON_TABLE in FROM would lose its PASSING / context expressions.
        if (function instanceof XmlTableFunction || function instanceof JsonTableFunction) {
            return function.accept(this, context);
        }
        // COUNT(*) counts rows; any other function's * (SQL Server CHECKSUM(*)) reads every column.
        boolean countsRows = function.getName() != null
                && ROW_COUNTING_FUNCTIONS.contains(function.getName().toLowerCase(Locale.ROOT));
        if (countsRows) {
            functionDepth++;
        }
        try {
            visitFunction(function, context);
        } finally {
            if (countsRows) {
                functionDepth--;
            }
        }
        return null;
    }

    private <S> void visitFunction(Function function, S context) {
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
        acceptOrderBy(function.getOrderByElements(), context);
        if (function.getLimit() != null) {
            accept(function.getLimit().getRowCount(), context);
            accept(function.getLimit().getOffset(), context);
        }
        if (function.getHavingClause() != null) {
            accept(function.getHavingClause().getExpression(), context);
        }
    }

    private <S> void acceptOrderBy(List<OrderByElement> elements, S context) {
        if (elements != null) {
            for (OrderByElement element : elements) {
                accept(element.getExpression(), context);
            }
        }
    }

    private <S> void accept(Expression expression, S context) {
        if (expression != null) {
            expression.accept(this, context);
        }
    }
}
