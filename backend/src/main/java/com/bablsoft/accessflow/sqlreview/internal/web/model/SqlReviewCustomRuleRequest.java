package com.bablsoft.accessflow.sqlreview.internal.web.model;

import com.bablsoft.accessflow.sqlreview.api.SqlReviewCustomRuleCommand;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewSeverity;
import com.bablsoft.accessflow.sqlreview.api.SqlRuleCategory;
import com.bablsoft.accessflow.sqlreview.api.SqlRuleCondition;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

/**
 * A custom SQL review rule on the wire (#1010) — the body of {@code POST}, of the total-replace
 * {@code PUT} and the {@code rule} of {@code POST /test}. {@code condition} is the
 * {@code "type"}-discriminated tree as JSON; the controller decodes it through the codec. The
 * limits mirror {@code SqlRuleConditionValidator} and the {@code V201} CHECK.
 */
public record SqlReviewCustomRuleRequest(
        @NotBlank(message = "{validation.sql_review_custom_rule_id.required}")
        @Pattern(regexp = "^custom_[a-z][a-z0-9_]{2,60}$",
                message = "{validation.sql_review_custom_rule_id.pattern}")
        String ruleId,

        @NotBlank(message = "{validation.sql_review_custom_rule_name.required}")
        @Size(max = 255, message = "{validation.sql_review_custom_rule_name.size}")
        String name,

        @Size(max = 2000, message = "{validation.sql_review_custom_rule_description.size}")
        String description,

        @NotBlank(message = "{validation.sql_review_custom_rule_message.required}")
        @Size(max = 500, message = "{validation.sql_review_custom_rule_message.size}")
        String message,

        @NotNull(message = "{validation.sql_review_custom_rule_category.required}")
        SqlRuleCategory category,

        @NotNull(message = "{validation.sql_review_custom_rule_severity.required}")
        SqlReviewSeverity defaultSeverity,

        Boolean enabled,

        @NotNull(message = "{validation.sql_review_custom_rule_condition.required}")
        JsonNode condition
) {
    public SqlReviewCustomRuleCommand toCommand(SqlRuleCondition decoded) {
        return new SqlReviewCustomRuleCommand(ruleId, name, description, message, category, defaultSeverity,
                enabled, decoded);
    }
}
