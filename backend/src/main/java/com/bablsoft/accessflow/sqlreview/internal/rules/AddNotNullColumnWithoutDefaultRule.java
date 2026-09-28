package com.bablsoft.accessflow.sqlreview.internal.rules;

import com.bablsoft.accessflow.sqlreview.api.SqlReviewFinding;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewSeverity;
import com.bablsoft.accessflow.sqlreview.api.SqlRuleCategory;
import net.sf.jsqlparser.statement.alter.Alter;
import net.sf.jsqlparser.statement.alter.AlterOperation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code ALTER TABLE … ADD [COLUMN] c <type> NOT NULL} with no {@code DEFAULT} (#1079): it fails on
 * a table that already has rows, or rewrites it. Identity, auto-increment and serial columns are
 * exempt — they fill themselves. One finding per column.
 */
public final class AddNotNullColumnWithoutDefaultRule implements SqlRule {

    public static final String ID = "add_not_null_column_without_default";

    @Override
    public String ruleId() {
        return ID;
    }

    @Override
    public SqlRuleCategory category() {
        return SqlRuleCategory.SCHEMA_CHANGE;
    }

    @Override
    public SqlReviewSeverity defaultSeverity() {
        return SqlReviewSeverity.WARN;
    }

    @Override
    public List<String> messageArgKeys() {
        return List.of("column");
    }

    @Override
    public List<SqlReviewFinding> apply(SqlRuleContext context, Map<String, List<String>> params) {
        if (!(context.statement() instanceof Alter alter)) {
            return List.of();
        }
        var findings = new ArrayList<SqlReviewFinding>();
        for (var target : ColumnDefinitions.of(alter, Set.of(AlterOperation.ADD))) {
            var column = target.column();
            if (column.getColDataType() != null && ColumnDefinitions.isNotNull(column)
                    && !ColumnDefinitions.hasDefault(column) && !ColumnDefinitions.isGenerated(column)) {
                findings.add(context.finding(this, alter.getTable(),
                        Map.of("column", ColumnDefinitions.qualified(alter, column.getColumnName()))));
            }
        }
        return findings;
    }
}
