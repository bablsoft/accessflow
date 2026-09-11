package com.bablsoft.accessflow.sqlreview.internal.rules;

import com.bablsoft.accessflow.sqlreview.api.SqlReviewFinding;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewSeverity;
import com.bablsoft.accessflow.sqlreview.api.SqlRuleCategory;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.truncate.Truncate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** {@code TRUNCATE} — one finding per table named. */
public final class TruncateStatementRule implements SqlRule {

    public static final String ID = "truncate_statement";

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
        return List.of("table");
    }

    @Override
    public List<SqlReviewFinding> apply(SqlRuleContext context, Map<String, List<String>> params) {
        if (!(context.statement() instanceof Truncate truncate)) {
            return List.of();
        }
        var tables = truncate.getTables() != null && !truncate.getTables().isEmpty()
                ? truncate.getTables()
                : List.of(truncate.getTable());
        var findings = new ArrayList<SqlReviewFinding>();
        for (Table table : tables) {
            findings.add(context.finding(this, table, Map.of("table", TableNames.normalize(table))));
        }
        return findings;
    }
}
