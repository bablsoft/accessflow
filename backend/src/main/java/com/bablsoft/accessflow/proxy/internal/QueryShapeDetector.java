package com.bablsoft.accessflow.proxy.internal;

import com.bablsoft.accessflow.core.api.QueryShape;
import net.sf.jsqlparser.expression.AnalyticExpression;
import net.sf.jsqlparser.expression.AnalyticType;
import net.sf.jsqlparser.expression.AnyComparisonExpression;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.JsonAggregateFunction;
import net.sf.jsqlparser.expression.operators.relational.ExistsExpression;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.create.table.CreateTable;
import net.sf.jsqlparser.statement.create.view.CreateView;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.merge.Merge;
import net.sf.jsqlparser.statement.select.LateralSubSelect;
import net.sf.jsqlparser.statement.select.ParenthesedFromItem;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.statement.select.WithItem;
import net.sf.jsqlparser.statement.update.Update;
import net.sf.jsqlparser.util.TablesNamesFinder;

import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Set;

/**
 * Detects the {@link QueryShape}s of one statement (#940) by walking its whole AST — select list,
 * FROM / JOIN, WHERE, GROUP BY, HAVING, ORDER BY, CTE bodies, INSERT…SELECT, UPDATE…FROM,
 * DELETE…USING and every nested subquery — on top of {@link TablesNamesFinder}'s traversal.
 *
 * <p>A parenthesised select is a subquery unless it is the statement's own query: the root, a
 * set-operation branch of it, a CTE body, or the query an INSERT / CREATE TABLE / CREATE VIEW takes
 * its rows from. JSqlParser raises on a few exotic shapes; that propagates, and the caller reports
 * the statement as not analyzed so a deny-list fails closed.
 */
final class QueryShapeDetector extends TablesNamesFinder<Void> {

    /**
     * The built-in aggregates of the in-process engines, matched by unqualified, case-insensitive
     * name, plus every {@code regr_*} function. {@code JSON_ARRAYAGG} / {@code JSON_OBJECTAGG} are
     * their own AST node and handled separately.
     */
    static final Set<String> AGGREGATE_FUNCTIONS = Set.of("count", "count_big", "sum", "avg", "min",
            "max", "string_agg", "array_agg", "group_concat", "listagg", "json_agg", "jsonb_agg",
            "json_object_agg", "jsonb_object_agg", "json_arrayagg", "json_objectagg", "xmlagg",
            "stddev", "stddev_pop", "stddev_samp", "std", "stdev", "stdevp", "variance", "var_pop",
            "var_samp", "var", "varp", "corr", "covar_pop", "covar_samp", "bool_and", "bool_or",
            "every", "bit_and", "bit_or", "bit_xor", "checksum_agg", "approx_count_distinct",
            "any_value", "collect", "median", "mode", "percentile_cont", "percentile_disc");

    private final Set<QueryShape> shapes = EnumSet.noneOf(QueryShape.class);
    private final Set<Select> ownQueries = Collections.newSetFromMap(new IdentityHashMap<>());
    // Selects already accounted for: the statement's own queries, the body of a parenthesised select
    // and the branches of a set operation. Any other select the walk reaches is a subquery — a bare
    // one appears as a function argument, e.g. ARRAY(SELECT …) or CURSOR(SELECT …).
    private final Set<Select> covered = Collections.newSetFromMap(new IdentityHashMap<>());

    private QueryShapeDetector() {
    }

    /**
     * @throws RuntimeException when JSqlParser cannot traverse the statement shape
     */
    static Set<QueryShape> detect(Statement statement) {
        var detector = new QueryShapeDetector();
        switch (statement) {
            case Select select -> detector.ownQuery(select);
            case Insert insert -> detector.ownQuery(insert.getSelect());
            case CreateTable create -> detector.ownQuery(create.getSelect());
            case CreateView view -> detector.ownQuery(view.getSelect());
            default -> { /* no top-level query */ }
        }
        detector.getTables(statement);
        return Set.copyOf(detector.shapes);
    }

    private void ownQuery(Select select) {
        if (select == null) {
            return;
        }
        ownQueries.add(select);
        covered.add(select);
        switch (select) {
            case ParenthesedSelect parenthesed -> ownQuery(parenthesed.getSelect());
            case SetOperationList list -> list.getSelects().forEach(this::ownQuery);
            default -> { /* a plain body */ }
        }
    }

    @Override
    public <S> Void visit(WithItem<?> withItem, S context) {
        shapes.add(QueryShape.CTE);
        ownQuery(withItem.getSelect());
        return super.visit(withItem, context);
    }

    @Override
    public <S> Void visit(PlainSelect plainSelect, S context) {
        flagIfUncovered(plainSelect);
        if (notEmpty(plainSelect.getJoins())) {
            shapes.add(QueryShape.JOIN);
        }
        if (plainSelect.getGroupBy() != null) {
            shapes.add(QueryShape.GROUP_BY);
        }
        if (plainSelect.getHaving() != null) {
            shapes.add(QueryShape.HAVING);
        }
        if (notEmpty(plainSelect.getWindowDefinitions())) {
            shapes.add(QueryShape.WINDOW_FUNCTION);
        }
        return super.visit(plainSelect, context);
    }

    @Override
    public <S> Void visit(SetOperationList list, S context) {
        flagIfUncovered(list);
        covered.addAll(list.getSelects());
        shapes.add(QueryShape.UNION);
        return super.visit(list, context);
    }

    @Override
    public <S> Void visit(ParenthesedSelect select, S context) {
        if (!ownQueries.contains(select)) {
            shapes.add(QueryShape.SUBQUERY);
        }
        covered.add(select);
        covered.add(select.getSelect());
        return super.visit(select, context);
    }

    @Override
    public <S> Void visit(LateralSubSelect select, S context) {
        shapes.add(QueryShape.SUBQUERY);
        return super.visit(select, context);
    }

    @Override
    public <S> Void visit(ExistsExpression exists, S context) {
        shapes.add(QueryShape.SUBQUERY);
        return super.visit(exists, context);
    }

    @Override
    public <S> Void visit(AnyComparisonExpression any, S context) {
        shapes.add(QueryShape.SUBQUERY);
        return super.visit(any, context);
    }

    /** {@code FROM (a JOIN b ON …)}: the joins live on the parenthesised item, not the select. */
    @Override
    public <S> Void visit(ParenthesedFromItem item, S context) {
        if (notEmpty(item.getJoins())) {
            shapes.add(QueryShape.JOIN);
        }
        return super.visit(item, context);
    }

    /** A MERGE always joins its target to its {@code USING} source. */
    @Override
    public <S> Void visit(Merge merge, S context) {
        shapes.add(QueryShape.JOIN);
        return super.visit(merge, context);
    }

    @Override
    public <S> Void visit(JsonAggregateFunction aggregate, S context) {
        shapes.add(QueryShape.AGGREGATE);
        if (aggregate.getAnalyticType() == AnalyticType.OVER
                || aggregate.getAnalyticType() == AnalyticType.WITHIN_GROUP_OVER) {
            shapes.add(QueryShape.WINDOW_FUNCTION);
        }
        return super.visit(aggregate, context);
    }

    @Override
    public <S> Void visit(Update update, S context) {
        if (notEmpty(update.getStartJoins()) || update.getFromItem() != null
                || notEmpty(update.getJoins())) {
            shapes.add(QueryShape.JOIN);
        }
        return super.visit(update, context);
    }

    @Override
    public <S> Void visit(Delete delete, S context) {
        if (notEmpty(delete.getUsingFromItemList()) || notEmpty(delete.getJoins())
                || (delete.getTables() != null && delete.getTables().size() > 1)) {
            shapes.add(QueryShape.JOIN);
        }
        return super.visit(delete, context);
    }

    @Override
    public <S> Void visit(Function function, S context) {
        if (isAggregate(function.getName())) {
            shapes.add(QueryShape.AGGREGATE);
        }
        return super.visit(function, context);
    }

    @Override
    public <S> Void visit(AnalyticExpression analytic, S context) {
        var type = analytic.getType();
        if (type == null || type == AnalyticType.OVER
                || type == AnalyticType.WITHIN_GROUP_OVER) {
            shapes.add(QueryShape.WINDOW_FUNCTION);
        }
        // An ordered-set (WITHIN GROUP) or FILTERed call is an aggregate whatever its name.
        if (isAggregate(analytic.getName()) || (type != null
                && type != AnalyticType.OVER)) {
            shapes.add(QueryShape.AGGREGATE);
        }
        return super.visit(analytic, context);
    }

    static boolean isAggregate(String name) {
        if (name == null) {
            return false;
        }
        var bare = SqlParserServiceImpl.normalizeIdentifier(name.substring(name.lastIndexOf('.') + 1))
                .strip().toLowerCase(Locale.ROOT);
        return AGGREGATE_FUNCTIONS.contains(bare) || bare.startsWith("regr_");
    }

    private void flagIfUncovered(Select select) {
        if (!covered.contains(select)) {
            shapes.add(QueryShape.SUBQUERY);
            covered.add(select);
        }
    }

    private static boolean notEmpty(Collection<?> items) {
        return items != null && !items.isEmpty();
    }
}
