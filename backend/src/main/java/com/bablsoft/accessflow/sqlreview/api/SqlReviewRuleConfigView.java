package com.bablsoft.accessflow.sqlreview.api;

import java.util.Map;

/**
 * One rule's configuration inside a ruleset.
 *
 * @param ruleId   the code-defined rule identifier
 * @param severity the assigned severity
 * @param params   rule-specific parameters (e.g. a name list for {@code disallowed_function});
 *                 never {@code null}
 */
public record SqlReviewRuleConfigView(String ruleId, SqlReviewSeverity severity, Map<String, String> params) {
    public SqlReviewRuleConfigView {
        params = params == null ? Map.of() : Map.copyOf(params);
    }
}
