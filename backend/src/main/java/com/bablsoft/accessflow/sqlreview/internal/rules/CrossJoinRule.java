package com.bablsoft.accessflow.sqlreview.internal.rules;

import com.bablsoft.accessflow.sqlreview.api.SqlReviewFinding;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewSeverity;
import com.bablsoft.accessflow.sqlreview.api.SqlRuleCategory;
import net.sf.jsqlparser.expression.BinaryExpression;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.operators.relational.ComparisonOperator;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.PlainSelect;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A Cartesian product: an explicit {@code CROSS JOIN}, a {@code JOIN} with neither {@code ON} nor
 * {@code USING} (and not {@code NATURAL} / {@code APPLY} — {@code CROSS APPLY} is a correlated
 * lateral join, not a product), or a comma join that no column-to-column comparison in the
 * {@code WHERE} correlates: a pair is correlating for a join when the two columns are not both
 * qualified with the same table and at least one side names the joined table or its alias.
 * Unqualified columns are given the benefit of the doubt — without the schema the rule cannot tell
 * which table they belong to, and a false positive here would be noise on every legacy query. A
 * column compared to an arithmetic expression ({@code a.id = b.id + 1}) is not recognised as a
 * correlation. Every select body is inspected, subqueries included.
 */
public final class CrossJoinRule implements SqlRule {

    public static final String ID = "cross_join";

    @Override
    public String ruleId() {
        return ID;
    }

    @Override
    public SqlRuleCategory category() {
        return SqlRuleCategory.PERFORMANCE;
    }

    @Override
    public SqlReviewSeverity defaultSeverity() {
        return SqlReviewSeverity.WARN;
    }

    @Override
    public List<String> messageArgKeys() {
        return List.of("table");
    }

    @Override
    public List<SqlReviewFinding> apply(SqlRuleContext context, Map<String, List<String>> params) {
        var findings = new ArrayList<SqlReviewFinding>();
        for (PlainSelect body : StatementWalker.walk(context.statement()).plainSelects()) {
            if (body.getJoins() == null) {
                continue;
            }
            var pairs = columnPairs(body.getWhere());
            for (Join join : body.getJoins()) {
                if (isCartesian(join, pairs)) {
                    findings.add(context.finding(this, join, Map.of("table", describe(join.getFromItem()))));
                }
            }
        }
        return findings;
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
            if (Tautologies.unwrap(comparison.getLeftExpression()) instanceof Column a
                    && Tautologies.unwrap(comparison.getRightExpression()) instanceof Column b) {
                pairs.add(new ColumnPair(qualifier(a), qualifier(b)));
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

    private static String qualifier(Column column) {
        return column.getTable() == null ? null : TableNames.normalize(column.getTable());
    }

    private static String describe(FromItem item) {
        if (item instanceof Table table) {
            return TableNames.normalize(table);
        }
        if (item != null && item.getAlias() != null && item.getAlias().getName() != null) {
            return TableNames.normalize(item.getAlias().getName());
        }
        return item == null ? "" : item.toString();
    }
}
