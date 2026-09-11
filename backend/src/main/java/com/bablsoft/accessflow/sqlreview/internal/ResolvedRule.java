package com.bablsoft.accessflow.sqlreview.internal;

import com.bablsoft.accessflow.sqlreview.api.SqlReviewSeverity;
import com.bablsoft.accessflow.sqlreview.internal.rules.SqlRule;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A rule together with the severity and params the resolved ruleset assigned to it (#862): the
 * config row's values when one exists, else the rule's built-in default severity and no params.
 */
public record ResolvedRule(SqlRule rule, SqlReviewSeverity severity, Map<String, List<String>> params) {

    public ResolvedRule {
        Objects.requireNonNull(rule, "rule");
        Objects.requireNonNull(severity, "severity");
        params = params == null ? Map.of() : Map.copyOf(params);
    }

    /** A rule at its built-in default severity with no configured params. */
    public static ResolvedRule defaults(SqlRule rule) {
        return new ResolvedRule(rule, rule.defaultSeverity(), Map.of());
    }
}
