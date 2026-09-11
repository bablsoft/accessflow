package com.bablsoft.accessflow.sqlreview.api;

import com.bablsoft.accessflow.core.api.DatasourceEnvironment;

import java.util.List;

/**
 * Creates a ruleset. {@code environment} {@code null} creates the organization-wide default;
 * {@code enabled} {@code null} defaults to {@code true}; {@code rules} {@code null} means no rules.
 */
public record CreateSqlReviewRulesetCommand(
        String name,
        String description,
        DatasourceEnvironment environment,
        Boolean enabled,
        List<SqlReviewRuleConfigView> rules
) {
    public CreateSqlReviewRulesetCommand {
        rules = rules == null ? List.of() : List.copyOf(rules);
    }
}
