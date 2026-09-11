package com.bablsoft.accessflow.sqlreview.internal.web.model;

import com.bablsoft.accessflow.sqlreview.api.SqlReviewRuleConfigView;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewSeverity;

import java.util.List;
import java.util.Map;

public record SqlReviewRuleConfigResponse(String ruleId, SqlReviewSeverity severity, Map<String, List<String>> params) {
    public static SqlReviewRuleConfigResponse from(SqlReviewRuleConfigView view) {
        return new SqlReviewRuleConfigResponse(view.ruleId(), view.severity(), view.params());
    }
}
