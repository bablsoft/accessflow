package com.bablsoft.accessflow.sqlreview.api;

import java.util.List;
import java.util.Objects;

/**
 * One rule as exposed by the rule catalog (#863): a built-in, with its name and description
 * rendered in the requested locale, or an organization's custom rule (#1009), with the name and
 * description its author wrote.
 *
 * @param ruleId          the stable rule identifier — code-defined, or {@code custom_<slug>}
 * @param defaultSeverity the severity the rule runs at when the resolved ruleset has no config row
 * @param params          the rule's declared parameters; empty for a parameterless rule
 * @param custom          whether this is an organization-defined custom rule
 */
public record SqlReviewRuleView(
        String ruleId,
        SqlRuleCategory category,
        SqlReviewSeverity defaultSeverity,
        String name,
        String description,
        List<SqlReviewRuleParamView> params,
        boolean custom
) {
    public SqlReviewRuleView {
        Objects.requireNonNull(ruleId, "ruleId");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(defaultSeverity, "defaultSeverity");
        params = params == null ? List.of() : List.copyOf(params);
    }
}
