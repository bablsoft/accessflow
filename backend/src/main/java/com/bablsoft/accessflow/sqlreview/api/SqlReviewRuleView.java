package com.bablsoft.accessflow.sqlreview.api;

import java.util.List;
import java.util.Objects;

/**
 * One built-in rule as exposed by the rule catalog (#863), with its name and description already
 * rendered in the requested locale.
 *
 * @param ruleId          the stable, code-defined rule identifier
 * @param defaultSeverity the severity the rule runs at when the resolved ruleset has no config row
 * @param params          the rule's declared parameters; empty for a parameterless rule
 */
public record SqlReviewRuleView(
        String ruleId,
        SqlRuleCategory category,
        SqlReviewSeverity defaultSeverity,
        String name,
        String description,
        List<SqlReviewRuleParamView> params
) {
    public SqlReviewRuleView {
        Objects.requireNonNull(ruleId, "ruleId");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(defaultSeverity, "defaultSeverity");
        params = params == null ? List.of() : List.copyOf(params);
    }
}
