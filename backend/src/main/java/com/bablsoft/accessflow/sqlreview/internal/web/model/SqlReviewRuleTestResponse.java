package com.bablsoft.accessflow.sqlreview.internal.web.model;

import com.bablsoft.accessflow.sqlreview.api.SqlReviewFinding;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewResult;

import java.util.List;
import java.util.function.Function;

/** The findings a draft custom rule would produce (#1010); messages are rendered. */
public record SqlReviewRuleTestResponse(List<SqlReviewFindingResponse> findings) {
    public static SqlReviewRuleTestResponse from(SqlReviewResult result,
                                                 Function<SqlReviewFinding, String> messageRenderer) {
        return new SqlReviewRuleTestResponse(result.findings().stream()
                .map(finding -> SqlReviewFindingResponse.from(finding, messageRenderer.apply(finding)))
                .toList());
    }
}
