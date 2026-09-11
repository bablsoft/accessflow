package com.bablsoft.accessflow.sqlreview.internal.rules;

import com.bablsoft.accessflow.sqlreview.api.SqlReviewFinding;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewSeverity;
import com.bablsoft.accessflow.sqlreview.api.SqlRuleCategory;
import net.sf.jsqlparser.statement.alter.Alter;
import net.sf.jsqlparser.statement.alter.AlterExpression;
import net.sf.jsqlparser.statement.alter.AlterOperation;
import net.sf.jsqlparser.statement.drop.Drop;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * {@code DROP TABLE} / {@code DROP SCHEMA} / {@code DROP DATABASE}, and {@code ALTER TABLE … DROP
 * COLUMN}. Narrower than {@code ddl_statement}: an admin who enables that one turns this off.
 */
public final class DropStatementRule implements SqlRule {

    public static final String ID = "drop_statement";

    private static final Set<String> DROP_TYPES = Set.of("TABLE", "SCHEMA", "DATABASE");

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
        return SqlReviewSeverity.BLOCK;
    }

    @Override
    public List<String> messageArgKeys() {
        return List.of("object_type", "name");
    }

    @Override
    public List<SqlReviewFinding> apply(SqlRuleContext context, Map<String, List<String>> params) {
        var findings = new ArrayList<SqlReviewFinding>();
        switch (context.statement()) {
            case Drop drop -> {
                var type = drop.getType() == null ? "" : drop.getType().toUpperCase(Locale.ROOT);
                if (DROP_TYPES.contains(type)) {
                    findings.add(context.finding(this, drop.getName(),
                            Map.of("object_type", type, "name", TableNames.normalize(drop.getName()))));
                }
            }
            case Alter alter -> {
                if (alter.getAlterExpressions() == null) {
                    break;
                }
                for (AlterExpression expression : alter.getAlterExpressions()) {
                    if (expression.getOperation() == AlterOperation.DROP && expression.getColumnName() != null) {
                        findings.add(context.finding(this, alter.getTable(), Map.of("object_type", "COLUMN",
                                "name", TableNames.normalize(alter.getTable()) + "."
                                        + TableNames.normalize(expression.getColumnName()))));
                    }
                }
            }
            default -> { /* not a drop */ }
        }
        return findings;
    }
}
