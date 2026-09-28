package com.bablsoft.accessflow.sqlreview.internal.rules;

import com.bablsoft.accessflow.sqlreview.api.SqlReviewFinding;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewSeverity;
import com.bablsoft.accessflow.sqlreview.api.SqlRuleCategory;
import net.sf.jsqlparser.statement.alter.Alter;
import net.sf.jsqlparser.statement.alter.AlterExpression;
import net.sf.jsqlparser.statement.alter.AlterOperation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code ALTER COLUMN c SET NOT NULL}, and Oracle's type-less {@code MODIFY c NOT NULL} (#1079): the
 * engine scans the whole table under an exclusive lock and fails if any row holds {@code NULL}. A
 * {@code MODIFY} that also restates the type is left to {@code alter_column_type}. One finding per
 * column.
 */
public final class SetNotNullOnExistingColumnRule implements SqlRule {

    public static final String ID = "set_not_null_on_existing_column";

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
        if (!(context.statement() instanceof Alter alter) || alter.getAlterExpressions() == null) {
            return List.of();
        }
        var findings = new ArrayList<SqlReviewFinding>();
        for (AlterExpression expression : alter.getAlterExpressions()) {
            if (expression.getColumnSetNotNullList() == null) {
                continue;
            }
            for (AlterExpression.ColumnSetNotNull column : expression.getColumnSetNotNullList()) {
                findings.add(finding(context, alter, column.getColumnName()));
            }
        }
        for (var target : ColumnDefinitions.of(alter, Set.of(AlterOperation.MODIFY))) {
            var column = target.column();
            if (column.getColDataType() == null && ColumnDefinitions.isNotNull(column)) {
                findings.add(finding(context, alter, column.getColumnName()));
            }
        }
        return findings;
    }

    private SqlReviewFinding finding(SqlRuleContext context, Alter alter, String column) {
        return context.finding(this, alter.getTable(), Map.of("column", ColumnDefinitions.qualified(alter, column)));
    }
}
