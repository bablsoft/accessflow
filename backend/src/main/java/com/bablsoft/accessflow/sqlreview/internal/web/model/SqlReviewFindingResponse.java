package com.bablsoft.accessflow.sqlreview.internal.web.model;

import com.bablsoft.accessflow.sqlreview.api.SqlReviewFinding;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewSeverity;

/** One finding on the wire; {@code lineNumber} is omitted when unknown, {@code message} is localized. */
public record SqlReviewFindingResponse(
        String ruleId,
        SqlReviewSeverity severity,
        int statementIndex,
        Integer lineNumber,
        String message
) {
    public static SqlReviewFindingResponse from(SqlReviewFinding finding, String message) {
        return new SqlReviewFindingResponse(finding.ruleId(), finding.severity(), finding.statementIndex(),
                finding.lineNumber(), message);
    }
}
