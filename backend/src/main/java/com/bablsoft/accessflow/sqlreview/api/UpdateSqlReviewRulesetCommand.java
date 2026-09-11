package com.bablsoft.accessflow.sqlreview.api;

import com.bablsoft.accessflow.core.api.DatasourceEnvironment;

import java.util.List;

/**
 * Partial update: {@code null} leaves a field unchanged. {@code clearEnvironment} turns the
 * ruleset into the organization-wide default (a non-null {@code environment} wins when both are
 * sent). A non-null {@code rules} list is a full replacement.
 */
public record UpdateSqlReviewRulesetCommand(
        String name,
        String description,
        DatasourceEnvironment environment,
        Boolean clearEnvironment,
        Boolean enabled,
        List<SqlReviewRuleConfigView> rules
) {
    public UpdateSqlReviewRulesetCommand {
        rules = rules == null ? null : List.copyOf(rules);
    }
}
