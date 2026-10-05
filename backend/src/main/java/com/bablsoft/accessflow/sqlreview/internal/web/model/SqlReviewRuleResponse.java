package com.bablsoft.accessflow.sqlreview.internal.web.model;

import com.bablsoft.accessflow.sqlreview.api.SqlReviewRuleView;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewSeverity;
import com.bablsoft.accessflow.sqlreview.api.SqlRuleCategory;

import java.util.List;

/** One catalog rule on the wire — a built-in with localized name and description, or a custom rule. */
public record SqlReviewRuleResponse(
        String ruleId,
        SqlRuleCategory category,
        SqlReviewSeverity defaultSeverity,
        String name,
        String description,
        List<SqlReviewRuleParamResponse> params,
        boolean custom
) {
    public static SqlReviewRuleResponse from(SqlReviewRuleView view) {
        return new SqlReviewRuleResponse(view.ruleId(), view.category(), view.defaultSeverity(), view.name(),
                view.description(), view.params().stream().map(SqlReviewRuleParamResponse::from).toList(), view.custom());
    }
}
