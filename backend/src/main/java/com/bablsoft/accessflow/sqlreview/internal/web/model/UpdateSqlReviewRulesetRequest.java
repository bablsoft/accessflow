package com.bablsoft.accessflow.sqlreview.internal.web.model;

import com.bablsoft.accessflow.core.api.DatasourceEnvironment;
import com.bablsoft.accessflow.sqlreview.api.UpdateSqlReviewRulesetCommand;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Replace-ruleset request ({@code PUT}). Every field is taken as sent: an omitted
 * {@code description} clears it, an omitted {@code environment} makes the ruleset the
 * organization-wide default, an omitted {@code enabled} means {@code true}, and {@code rules} is
 * the complete new rule-config set. The api command is a partial update; this record is what makes
 * {@code PUT} total.
 */
public record UpdateSqlReviewRulesetRequest(
        @NotBlank(message = "{validation.sql_review_ruleset_name.required}")
        @Size(max = 255, message = "{validation.sql_review_ruleset_name.size}")
        String name,
        @Size(max = 2000, message = "{validation.sql_review_ruleset_description.size}")
        String description,
        DatasourceEnvironment environment,
        Boolean enabled,
        @Valid List<SqlReviewRuleConfigRequest> rules
) {
    public UpdateSqlReviewRulesetCommand toCommand() {
        return new UpdateSqlReviewRulesetCommand(
                name,
                description == null ? "" : description,
                environment,
                environment == null,
                enabled == null || enabled,
                CreateSqlReviewRulesetRequest.toViews(rules));
    }
}
