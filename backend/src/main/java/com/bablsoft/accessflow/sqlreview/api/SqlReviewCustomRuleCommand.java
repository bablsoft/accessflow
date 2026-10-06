package com.bablsoft.accessflow.sqlreview.api;

/**
 * A custom rule as written by an admin (#1010) — the body of a create, of a total-replace update
 * and of a draft under test. {@code enabled} {@code null} means {@code true}.
 */
public record SqlReviewCustomRuleCommand(
        String ruleId,
        String name,
        String description,
        String message,
        SqlRuleCategory category,
        SqlReviewSeverity defaultSeverity,
        Boolean enabled,
        SqlRuleCondition condition
) {
}
