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
import java.util.List;
import java.util.Map;

/**
 * A Cartesian product: an explicit {@code CROSS JOIN}, a {@code JOIN} with neither {@code ON} nor
 * {@code USING} (and not {@code NATURAL} / {@code APPLY}), or a comma join whose {@code WHERE}
 * carries no column-to-column comparison between two differently qualified tables. Unqualified
 * columns are given the benefit of the doubt — without the schema the rule cannot tell which
 * table they belong to, and a false positive here would be noise on every legacy query.
 * Every select body is inspected, subqueries included.
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
            boolean correlated = hasColumnCorrelation(body.getWhere());
            for (Join join : body.getJoins()) {
                if (isCartesian(join, correlated)) {
                    findings.add(context.finding(this, join, Map.of("table", describe(join.getFromItem()))));
                }
            }
        }
        return findings;
    }

    private static boolean isCartesian(Join join, boolean whereCorrelated) {
        if (join.isCross()) {
            return true;
        }
        if (join.isSimple()) {
            return !whereCorrelated;
        }
        if (join.isNatural() || join.isApply()) {
            return false;
        }
        boolean hasOn = join.getOnExpressions() != null && !join.getOnExpressions().isEmpty();
        boolean hasUsing = join.getUsingColumns() != null && !join.getUsingColumns().isEmpty();
        return !hasOn && !hasUsing;
    }

    /**
     * True when {@code where} compares two columns that are not both qualified with the same
     * table — {@code t.id = u.id}, or {@code id = uid} where the owners are unknown.
     */
    static boolean hasColumnCorrelation(Expression where) {
        var expression = Tautologies.unwrap(where);
        if (expression instanceof ComparisonOperator comparison) {
            return qualifiedPair(Tautologies.unwrap(comparison.getLeftExpression()),
                    Tautologies.unwrap(comparison.getRightExpression()));
        }
        if (expression instanceof BinaryExpression binary) {
            return hasColumnCorrelation(binary.getLeftExpression())
                    || hasColumnCorrelation(binary.getRightExpression());
        }
        return false;
    }

    private static boolean qualifiedPair(Expression left, Expression right) {
        if (!(left instanceof Column a) || !(right instanceof Column b)) {
            return false;
        }
        String qa = qualifier(a);
        String qb = qualifier(b);
        return qa == null || qb == null || !qa.equals(qb);
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
