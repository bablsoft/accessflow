package com.bablsoft.accessflow.sqlreview.internal.rules;

import net.sf.jsqlparser.expression.BinaryExpression;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.operators.relational.ComparisonOperator;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.PlainSelect;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Cartesian-product detection shared by {@code cross_join} and the custom-rule
 * {@code join_without_condition} fact (#1009), so the two cannot drift. A join is Cartesian when it
 * is an explicit {@code CROSS JOIN}, a {@code JOIN} with neither {@code ON} nor {@code USING} (and
 * not {@code NATURAL} / {@code APPLY} — {@code CROSS APPLY} is a correlated lateral join, not a
 * product), or a comma join that no column-to-column comparison in the {@code WHERE} correlates: a
 * pair is correlating for a join when the two columns are not both qualified with the same table
 * and at least one side names the joined table or its alias. Unqualified columns are given the
 * benefit of the doubt — without the schema the table they belong to is unknown. A column compared
 * to an arithmetic expression ({@code a.id = b.id + 1}) is not recognised as a correlation. Every
 * select body is inspected, subqueries included.
 */
final class CartesianJoins {

    private CartesianJoins() {
    }

    /** Every Cartesian join of every select body the walker saw, in traversal order. */
    static List<Join> find(StatementWalker walker) {
        var out = new ArrayList<Join>();
        for (PlainSelect body : walker.plainSelects()) {
            if (body.getJoins() == null) {
                continue;
            }
            var pairs = columnPairs(body.getWhere());
            for (Join join : body.getJoins()) {
                if (isCartesian(join, pairs)) {
                    out.add(join);
                }
            }
        }
        return out;
    }

    /** The normalised name (or alias) of the joined item, for messages. */
    static String describe(FromItem item) {
        if (item instanceof Table table) {
            return TableNames.normalize(table);
        }
        if (item != null && item.getAlias() != null && item.getAlias().getName() != null) {
            return TableNames.normalize(item.getAlias().getName());
        }
        return item == null ? "" : item.toString();
    }

    private static boolean isCartesian(Join join, List<ColumnPair> pairs) {
        if (join.isApply() || join.isNatural()) {
            return false;
        }
        if (join.isCross()) {
            return true;
        }
        if (join.isSimple()) {
            return !correlates(join, pairs);
        }
        boolean hasOn = join.getOnExpressions() != null && !join.getOnExpressions().isEmpty();
        boolean hasUsing = join.getUsingColumns() != null && !join.getUsingColumns().isEmpty();
        return !hasOn && !hasUsing;
    }

    /** The two qualifiers of a column-to-column comparison; {@code null} = unqualified. */
    record ColumnPair(String left, String right) {

        boolean sameTable() {
            return left != null && left.equals(right);
        }

        boolean mentions(Set<String> names) {
            return left == null || right == null || names.contains(left) || names.contains(right);
        }
    }

    /** Every column-to-column comparison in {@code where}, at any nesting depth. */
    static List<ColumnPair> columnPairs(Expression where) {
        var pairs = new ArrayList<ColumnPair>();
        collectPairs(where, pairs);
        return pairs;
    }

    private static void collectPairs(Expression where, List<ColumnPair> pairs) {
        var expression = Tautologies.unwrap(where);
        if (expression instanceof ComparisonOperator comparison) {
            var a = Tautologies.unwrap(comparison.getLeftExpression());
            var b = Tautologies.unwrap(comparison.getRightExpression());
            if (ColumnLike.isColumn(a) && ColumnLike.isColumn(b)) {
                pairs.add(new ColumnPair(ColumnLike.qualifier(a), ColumnLike.qualifier(b)));
            }
            return;
        }
        if (expression instanceof BinaryExpression binary) {
            collectPairs(binary.getLeftExpression(), pairs);
            collectPairs(binary.getRightExpression(), pairs);
        }
    }

    /** A comma join is correlated when some pair crosses tables and touches this join's table. */
    private static boolean correlates(Join join, List<ColumnPair> pairs) {
        var names = namesOf(join.getFromItem());
        for (ColumnPair pair : pairs) {
            if (!pair.sameTable() && pair.mentions(names)) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> namesOf(FromItem item) {
        var names = new HashSet<String>();
        if (item instanceof Table table) {
            names.add(TableNames.normalize(table));
            names.add(TableNames.bareName(TableNames.normalize(table)));
        }
        if (item != null && item.getAlias() != null && item.getAlias().getName() != null) {
            names.add(TableNames.normalize(item.getAlias().getName()));
        }
        return names;
    }
}
