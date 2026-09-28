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
 * An in-place column type change (#1079) — PostgreSQL {@code ALTER COLUMN c TYPE …}, SQL Server
 * {@code ALTER COLUMN c <type>}, MySQL {@code MODIFY} / {@code CHANGE}, Oracle {@code MODIFY (…)}.
 * It can rewrite the table and truncate or reject existing values. The statement carries only the
 * new type, so whether the change narrows the column is not decidable from the AST; every
 * redefinition is reported. One finding per column.
 */
public final class AlterColumnTypeRule implements SqlRule {

    public static final String ID = "alter_column_type";

    private static final Set<AlterOperation> REDEFINING = Set.of(
            AlterOperation.ALTER, AlterOperation.MODIFY, AlterOperation.CHANGE);

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
        return List.of("column", "type");
    }

    @Override
    public List<SqlReviewFinding> apply(SqlRuleContext context, Map<String, List<String>> params) {
        if (!(context.statement() instanceof Alter alter)) {
            return List.of();
        }
        var findings = new ArrayList<SqlReviewFinding>();
        for (var target : ColumnDefinitions.of(alter, REDEFINING)) {
            var type = target.column().getColDataType();
            if (type == null) {
                continue;
            }
            var oldName = target.expression().getColumnOldName();
            var column = oldName != null ? oldName : target.column().getColumnName();
            findings.add(context.finding(this, alter.getTable(), Map.of(
                    "column", ColumnDefinitions.qualified(alter, column),
                    "type", type.toString())));
        }
        return findings;
    }
}
