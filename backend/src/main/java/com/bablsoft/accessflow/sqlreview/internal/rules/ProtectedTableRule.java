package com.bablsoft.accessflow.sqlreview.internal.rules;

import com.bablsoft.accessflow.core.api.GlobMatcher;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewFinding;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewSeverity;
import com.bablsoft.accessflow.sqlreview.api.SqlRuleCategory;
import net.sf.jsqlparser.schema.Table;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Any statement touching a table that matches a configured glob. Param {@code globs} — the
 * routing-policy glob dialect ({@code payroll.*}, {@code *.audit_log}); a glob is tried against the
 * normalised {@code schema.table} name and against the bare table name, so {@code audit_log}
 * matches both {@code audit_log} and {@code public.audit_log}. No globs, no findings.
 */
public final class ProtectedTableRule implements SqlRule {

    public static final String ID = "protected_table";

    public static final String PARAM_GLOBS = "globs";

    private static final SqlRuleParam GLOBS = new SqlRuleParam(PARAM_GLOBS, true, List.of(),
            Pattern.compile("[A-Za-z0-9_$*.\\-]+"), "error.sql_review_rule_glob_invalid");

    @Override
    public String ruleId() {
        return ID;
    }

    @Override
    public SqlRuleCategory category() {
        return SqlRuleCategory.DATA_PROTECTION;
    }

    @Override
    public SqlReviewSeverity defaultSeverity() {
        return SqlReviewSeverity.BLOCK;
    }

    @Override
    public List<SqlRuleParam> params() {
        return List.of(GLOBS);
    }

    @Override
    public List<String> messageArgKeys() {
        return List.of("table", "glob");
    }

    @Override
    public List<SqlReviewFinding> apply(SqlRuleContext context, Map<String, List<String>> params) {
        var globs = params == null ? null : params.get(PARAM_GLOBS);
        if (globs == null || globs.isEmpty()) {
            return List.of();
        }
        var walker = StatementWalker.walk(context.statement());
        var findings = new ArrayList<SqlReviewFinding>();
        for (String table : TableNames.referencedTables(context.statement())) {
            var matched = firstMatch(globs, table);
            if (matched != null) {
                findings.add(context.finding(this, anchorFor(walker.tables(), table),
                        Map.of("table", table, "glob", matched)));
            }
        }
        return findings;
    }

    private static String firstMatch(List<String> globs, String table) {
        var bare = TableNames.bareName(table);
        for (String glob : globs) {
            if (glob != null && !glob.isBlank()
                    && (GlobMatcher.matches(glob, table) || GlobMatcher.matches(glob, bare))) {
                return glob.trim();
            }
        }
        return null;
    }

    private static Table anchorFor(List<Table> tables, String normalized) {
        for (Table table : tables) {
            if (normalized.equals(TableNames.normalize(table))) {
                return table;
            }
        }
        return null;
    }
}
