package com.bablsoft.accessflow.sqlreview.internal.web.model;

import com.bablsoft.accessflow.sqlreview.api.SqlReviewFinding;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewResult;

import java.util.List;
import java.util.function.Function;

/** The evaluation outcome on the wire; {@code applicable} is {@code false} for engine-plugin datasources. */
public record SqlReviewEvaluationResponse(boolean applicable, List<SqlReviewFindingResponse> findings) {
    public static SqlReviewEvaluationResponse from(SqlReviewResult result,
                                                   Function<SqlReviewFinding, String> messageRenderer) {
        return new SqlReviewEvaluationResponse(result.applicable(), result.findings().stream()
                .map(finding -> SqlReviewFindingResponse.from(finding, messageRenderer.apply(finding)))
                .toList());
    }
}
