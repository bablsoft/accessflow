package com.bablsoft.accessflow.sqlreview.internal.web.model;

import com.bablsoft.accessflow.core.api.DatasourceEnvironment;
import com.bablsoft.accessflow.sqlreview.api.CreateSqlReviewRulesetCommand;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewRuleConfigView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Create-ruleset request. A missing {@code environment} creates the organization-wide default;
 * a missing {@code enabled} means {@code true}; a missing {@code rules} list means no rows (every
 * rule at its built-in severity).
 */
public record CreateSqlReviewRulesetRequest(
        @NotBlank(message = "{validation.sql_review_ruleset_name.required}")
        @Size(max = 255, message = "{validation.sql_review_ruleset_name.size}")
        String name,
        @Size(max = 2000, message = "{validation.sql_review_ruleset_description.size}")
        String description,
        DatasourceEnvironment environment,
        Boolean enabled,
        @Valid List<SqlReviewRuleConfigRequest> rules
) {
    public CreateSqlReviewRulesetCommand toCommand() {
        return new CreateSqlReviewRulesetCommand(name, description, environment, enabled, toViews(rules));
    }

    static List<SqlReviewRuleConfigView> toViews(
            List<SqlReviewRuleConfigRequest> rules) {
        return rules == null ? List.of() : rules.stream().map(SqlReviewRuleConfigRequest::toView).toList();
    }
}
