package com.bablsoft.accessflow.sqlreview.internal.rules;

import com.bablsoft.accessflow.sqlreview.api.SqlReviewFinding;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewSeverity;
import com.bablsoft.accessflow.sqlreview.api.SqlRuleCategory;
import net.sf.jsqlparser.expression.Function;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A call to a banned function anywhere in the statement. Param {@code names} — matched on the
 * unqualified, case-insensitive name, so {@code pg_catalog.pg_sleep(5)} is caught by
 * {@code pg_sleep}; absent or empty falls back to the built-in sleep / benchmark / file-read set.
 */
public final class DisallowedFunctionRule implements SqlRule {

    public static final String ID = "disallowed_function";

    public static final String PARAM_NAMES = "names";

    public static final List<String> DEFAULT_NAMES = List.of("pg_sleep", "sleep", "benchmark", "load_file");

    private static final SqlRuleParam NAMES = new SqlRuleParam(PARAM_NAMES, true, DEFAULT_NAMES);

    @Override
    public String ruleId() {
        return ID;
    }

    @Override
    public SqlRuleCategory category() {
        return SqlRuleCategory.STATEMENT_SAFETY;
    }

    @Override
    public SqlReviewSeverity defaultSeverity() {
        return SqlReviewSeverity.BLOCK;
    }

    @Override
    public List<SqlRuleParam> params() {
        return List.of(NAMES);
    }

    @Override
    public List<String> messageArgKeys() {
        return List.of("function");
    }

    @Override
    public List<SqlReviewFinding> apply(SqlRuleContext context, Map<String, List<String>> params) {
        var banned = bannedNames(params);
        var findings = new ArrayList<SqlReviewFinding>();
        for (Function function : StatementWalker.walk(context.statement()).functions()) {
            var name = bareName(function);
            if (banned.contains(name)) {
                findings.add(context.finding(this, function, Map.of("function", name)));
            }
        }
        return findings;
    }

    static Set<String> bannedNames(Map<String, List<String>> params) {
        var configured = params == null ? null : params.get(PARAM_NAMES);
        var source = configured == null || configured.isEmpty() ? DEFAULT_NAMES : configured;
        var names = new HashSet<String>();
        for (String name : source) {
            if (name != null && !name.isBlank()) {
                names.add(TableNames.bareName(TableNames.normalize(name.trim())));
            }
        }
        return names;
    }

    private static String bareName(Function function) {
        var parts = function.getMultipartName();
        var raw = parts == null || parts.isEmpty() ? function.getName() : parts.get(parts.size() - 1);
        return TableNames.normalize(raw == null ? "" : raw).toLowerCase(Locale.ROOT);
    }
}
