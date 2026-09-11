package com.bablsoft.accessflow.sqlreview.internal.web.model;

import com.bablsoft.accessflow.sqlreview.api.SqlReviewRuleConfigView;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewSeverity;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

/**
 * One rule's severity and params inside a create / update ruleset request. {@code params} is a JSON
 * object of string arrays keyed by the rule's declared param key; absent means no params. Rule-level
 * validity (known id, declared keys, value syntax) is the service's {@code SqlRuleParamsValidator}.
 */
public record SqlReviewRuleConfigRequest(
        @NotBlank(message = "{validation.sql_review_rule_id.required}")
        @Size(max = 100, message = "{validation.sql_review_rule_id.size}")
        String ruleId,
        @NotNull(message = "{validation.sql_review_rule_severity.required}")
        SqlReviewSeverity severity,
        Map<String, List<String>> params
) {
    public SqlReviewRuleConfigView toView() {
        return new SqlReviewRuleConfigView(ruleId, severity, params);
    }
}
