package com.bablsoft.accessflow.sqlreview.internal.web.model;

import com.bablsoft.accessflow.sqlreview.api.SqlReviewRuleParamView;

import java.util.List;

public record SqlReviewRuleParamResponse(String key, boolean required, List<String> defaults, String valuePattern) {
    public static SqlReviewRuleParamResponse from(SqlReviewRuleParamView view) {
        return new SqlReviewRuleParamResponse(view.key(), view.required(), view.defaults(), view.valuePattern());
    }
}
